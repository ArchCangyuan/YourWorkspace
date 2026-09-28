package net.archcangyuan.codeserverapp;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Watches one relayed RDP session. It records, for diagnosing disconnects, the
 * virtual channel messages the client sent (with their chunk headers) and the
 * last bytes the server sent before the connection ended, and a running log
 * of the clipboard (cliprdr) conversation in both directions.
 */
final class RdpTrace {
    private static final int IO_CHANNEL_GUESS = 1003;
    private static final int MAX_ENTRIES = 12;
    private static final int TAIL_BYTES = 48;

    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private final byte[] tail = new byte[TAIL_BYTES];
    private int tailLength;
    private long bytesUp;
    private long bytesDown;
    private byte[] pending = new byte[0];
    private int cliprdrIndex = -1;
    private int cliprdrChannel = -1;
    private byte[] serverPending = new byte[0];
    private final ArrayDeque<String> clipEvents = new ArrayDeque<>();
    private static final int MAX_CLIP_EVENTS = 40;
    // IronRDP's clipboard Capabilities and Temporary Directory PDUs, kept to
    // replay when the PC's clipboard service (rdpclip) restarts: IronRDP
    // answers a later Monitor Ready without them, and without the client's
    // file-stream capability the PC never offers uploaded files for pasting.
    private byte[] clientCapabilities;
    private byte[] clientTempDirectory;
    private int monitorReadyCount;
    private int earlyDropped;
    private boolean clientSynced;
    private byte[] pendingReplay;

    // The virtual channel message being chunked, if any.
    private int chunkChannel = -1;
    private long chunkTotal;
    private long chunkSum;
    private int chunkCount;
    private int chunkMaxSize;
    private String chunkFlags = "";

    /** Records a message the client sends to the server. */
    /**
     * Records a message the client sends to the server and returns what to
     * forward: the message itself, or a copy without clipboard PDUs sent
     * before the server's first Monitor Ready. IronRDP sends its clipboard
     * handshake early when the phone's clipboard has content at connect, and
     * again after Monitor Ready; given both, the PC's rdpclip answers once and
     * then stops answering the clipboard channel altogether.
     */
    /** Clipboard rewrites, switchable from the page for diagnosis. */
    static volatile boolean addFileContents = false;
    static volatile boolean clearClipDataLocking = true;

    static boolean setOption(String name, boolean value) {
        switch (name) {
            case "addFileContents":
                addFileContents = value;
                return true;
            case "clearClipDataLocking":
                clearClipDataLocking = value;
                return true;
            default:
                return false;
        }
    }

    synchronized byte[] fromClient(byte[] message) {
        byte[] forward = message;
        if (clientSynced && pending.length == 0 && cliprdrChannel >= 0) {
            if (monitorReadyCount == 0) {
                forward = withoutEarlyClipboard(message);
            } else {
                forward = addFileContents ? withFileContentsFormat(message) : message;
                forward = clearClipDataLocking ? withoutClipDataLocking(forward) : forward;
            }
        }
        record(forward);
        return forward;
    }

    /**
     * Adds a FileContents entry to file lists (FileGroupDescriptorW and
     * Preferred DropEffect only). Off by default: Windows left such lists
     * unanswered, while the plain list pastes fine once clipboard locking is
     * off (checked on the PC, 2026-09-28).
     */
    private byte[] withFileContentsFormat(byte[] message) {
        java.io.ByteArrayOutputStream out = null;
        int offset = 0;
        int copied = 0;
        while (offset < message.length) {
            int length = pduLength(message, offset);
            if (length <= 0 || offset + length > message.length) {
                break;
            }
            byte[] rewritten = (message[offset] & 0xFF) == 3
                ? fileListWithFileContents(message, offset, length)
                : null;
            if (rewritten != null) {
                if (out == null) {
                    out = new java.io.ByteArrayOutputStream(message.length + 64);
                }
                out.write(message, copied, offset - copied);
                out.write(rewritten, 0, rewritten.length);
                copied = offset + length;
            }
            offset += length;
        }
        if (out == null) {
            return message;
        }
        out.write(message, copied, message.length - copied);
        clipEvents.addLast(String.format(Locale.US,
            "%tT.%<tL gateway added FileContents to the file list", System.currentTimeMillis()));
        return out.toByteArray();
    }

    /**
     * Clears CAN_LOCK_CLIPDATA in the client's clipboard capabilities. With
     * locking negotiated the PC locks the phone's first (text) format list and
     * then left every later file list unanswered; without it rdpclip asks for
     * file contents without a lock id, which IronRDP serves from the current
     * file list.
     */
    private byte[] withoutClipDataLocking(byte[] message) {
        byte[] result = message;
        int offset = 0;
        while (offset < result.length) {
            int length = pduLength(result, offset);
            if (length <= 0 || offset + length > result.length) {
                break;
            }
            int mcs = offset + 7;
            if ((result[offset] & 0xFF) == 3 && isClipboardPdu(result, offset, length)) {
                int userData = mcs + 7 + (((result[mcs + 6] & 0xFF) & 0x80) != 0 ? 1 : 0);
                int body = userData + 8;
                if (body + 24 <= offset + length
                    && (le32(result, userData + 4) & 0x3) == 0x3
                    && (result[body] & 0xFF) == 7 && result[body + 1] == 0
                    && (result[body + 12] & 0xFF) == 1 && result[body + 13] == 0
                    && (result[body + 20] & 0x10) != 0) {
                    if (result == message) {
                        result = message.clone();
                    }
                    result[body + 20] &= ~0x10;
                    clipEvents.addLast(String.format(Locale.US,
                        "%tT.%<tL gateway turned off clipboard locking", System.currentTimeMillis()));
                }
            }
            offset += length;
        }
        return result;
    }

    private static final int FILE_CONTENTS_FORMAT_ID = 0xC0FC;

    private byte[] fileListWithFileContents(byte[] data, int offset, int length) {
        int end = offset + length;
        int mcs = offset + 7;
        if (length < 16 || (data[mcs] & 0xFF) != 0x64) {
            return null;
        }
        int channel = ((data[mcs + 3] & 0xFF) << 8) | (data[mcs + 4] & 0xFF);
        boolean longLength = ((data[mcs + 6] & 0xFF) & 0x80) != 0;
        int userData = mcs + 7 + (longLength ? 1 : 0);
        if (channel != cliprdrChannel || userData + 16 > end) {
            return null;
        }
        long chunkFlags = le32(data, userData + 4);
        int body = userData + 8;
        int type = (data[body] & 0xFF) | ((data[body + 1] & 0xFF) << 8);
        if ((chunkFlags & 0x3) != 0x3 || type != 2) {
            return null;
        }
        String formats = formatNames(data, body + 8, end);
        if (!formats.contains("FileGroupDescriptorW") || formats.contains("FileContents")) {
            return null;
        }
        byte[] entry = new byte[4 + ("FileContents".length() + 1) * 2];
        entry[0] = (byte) FILE_CONTENTS_FORMAT_ID;
        entry[1] = (byte) (FILE_CONTENTS_FORMAT_ID >>> 8);
        String name = "FileContents";
        for (int index = 0; index < name.length(); index++) {
            entry[4 + index * 2] = (byte) name.charAt(index);
        }
        int oldUserLength = end - userData;
        int newUserLength = oldUserLength + entry.length;
        if (newUserLength - 8 > 1600 || newUserLength > 0x3FFF) {
            return null;
        }
        java.io.ByteArrayOutputStream pdu = new java.io.ByteArrayOutputStream(length + entry.length + 1);
        // MCS Send Data Request header up to (not including) the PER length.
        byte[] head = java.util.Arrays.copyOfRange(data, offset + 4, mcs + 6);
        byte[] user = java.util.Arrays.copyOfRange(data, userData, end);
        writeLe32(user, 0, le32(user, 0) + entry.length);
        writeLe32(user, 12, le32(user, 12) + entry.length);
        byte[] perLength = newUserLength < 0x80
            ? new byte[] { (byte) newUserLength }
            : new byte[] { (byte) (0x80 | (newUserLength >>> 8)), (byte) newUserLength };
        int tpktLength = 4 + head.length + perLength.length + newUserLength;
        pdu.write(3);
        pdu.write(0);
        pdu.write(tpktLength >>> 8);
        pdu.write(tpktLength & 0xFF);
        pdu.write(head, 0, head.length);
        pdu.write(perLength, 0, perLength.length);
        pdu.write(user, 0, user.length);
        pdu.write(entry, 0, entry.length);
        return pdu.toByteArray();
    }

    private static void writeLe32(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private byte[] withoutEarlyClipboard(byte[] message) {
        java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream(message.length);
        int offset = 0;
        boolean dropped = false;
        while (offset < message.length) {
            int length = pduLength(message, offset);
            if (length <= 0 || offset + length > message.length) {
                kept.write(message, offset, message.length - offset);
                break;
            }
            if ((message[offset] & 0xFF) == 3 && isClipboardPdu(message, offset, length)) {
                dropped = true;
                earlyDropped += 1;
            } else {
                kept.write(message, offset, length);
            }
            offset += length;
        }
        if (!dropped) {
            return message;
        }
        clipEvents.addLast(String.format(Locale.US,
            "%tT.%<tL gateway held back an early clipboard PDU (before the PC's Monitor Ready)",
            System.currentTimeMillis()));
        return kept.toByteArray();
    }

    private boolean isClipboardPdu(byte[] data, int offset, int length) {
        int mcs = offset + 7;
        if (length < 14 || (data[mcs] & 0xFF) != 0x64) {
            return false;
        }
        int channel = ((data[mcs + 3] & 0xFF) << 8) | (data[mcs + 4] & 0xFF);
        return channel == cliprdrChannel;
    }

    private void record(byte[] message) {
        bytesUp += message.length;
        // Before RDP proper the client sends CredSSP (ASN.1) messages, which
        // would be misread as PDUs and throw the framing off for good. Start
        // at the first TPKT (MCS Connect Initial).
        if (!clientSynced) {
            if (message.length < 4 || message[0] != 3 || message[1] != 0) {
                return;
            }
            clientSynced = true;
            pending = new byte[0];
        }
        if (cliprdrIndex < 0) {
            cliprdrIndex = findClientChannel(message, "cliprdr");
        }
        if (pending.length > 0) {
            // A PDU split across messages.
            byte[] joined = new byte[pending.length + message.length];
            System.arraycopy(pending, 0, joined, 0, pending.length);
            System.arraycopy(message, 0, joined, pending.length, message.length);
            parse(joined);
            return;
        }
        parse(message);
    }

    private void parse(byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            int length = pduLength(data, offset);
            if (length <= 0) {
                pending = new byte[0];
                return;
            }
            if (offset + length > data.length) {
                break;
            }
            if ((data[offset] & 0xFF) == 3) {
                slowPath(data, offset, length);
                clipEvent("app->PC", data, offset, length, 0x64);
            }
            offset += length;
        }
        pending = java.util.Arrays.copyOfRange(data, offset, data.length);
    }

    synchronized void fromServer(byte[] data, int count) {
        bytesDown += count;
        if (cliprdrChannel < 0 && cliprdrIndex >= 0) {
            cliprdrChannel = findServerChannelId(data, count, cliprdrIndex);
        }
        if (cliprdrChannel >= 0) {
            byte[] joined = new byte[serverPending.length + count];
            System.arraycopy(serverPending, 0, joined, 0, serverPending.length);
            System.arraycopy(data, 0, joined, serverPending.length, count);
            int offset = 0;
            while (offset < joined.length) {
                int length = pduLength(joined, offset);
                if (length <= 0) {
                    joined = new byte[0];
                    offset = 0;
                    break;
                }
                if (offset + length > joined.length) {
                    break;
                }
                if ((joined[offset] & 0xFF) == 3) {
                    clipEvent("PC->app", joined, offset, length, 0x68);
                }
                offset += length;
            }
            serverPending = java.util.Arrays.copyOfRange(joined, offset, joined.length);
        }
        if (count >= TAIL_BYTES) {
            System.arraycopy(data, count - TAIL_BYTES, tail, 0, TAIL_BYTES);
            tailLength = TAIL_BYTES;
            return;
        }
        int keep = Math.min(tailLength, TAIL_BYTES - count);
        System.arraycopy(tail, tailLength - keep, tail, 0, keep);
        System.arraycopy(data, 0, tail, keep, count);
        tailLength = keep + count;
    }

    synchronized String summary() {
        StringBuilder text = new StringBuilder();
        text.append("up ").append(bytesUp).append(" B, down ").append(bytesDown).append(" B")
            .append(", cliprdr ch").append(cliprdrChannel)
;
        if (chunkChannel >= 0) {
            text.append("; unfinished ").append(describeChunks());
        }
        for (String entry : entries) {
            text.append("; ").append(entry);
        }
        text.append("; last from PC:");
        for (int index = 0; index < tailLength; index++) {
            text.append(String.format(Locale.US, " %02x", tail[index] & 0xFF));
        }
        return text.toString();
    }

    /**
     * PDUs to send the server before forwarding what it just sent (the
     * client's clipboard capabilities after an rdpclip restart), or null.
     */
    synchronized byte[] takeReplay() {
        byte[] replay = pendingReplay;
        pendingReplay = null;
        if (replay != null) {
            clipEvents.addLast(String.format(Locale.US,
                "%tT.%<tL gateway replayed Capabilities + TempDirectory (PC clipboard restarted)",
                System.currentTimeMillis()));
        }
        return replay;
    }

    /** The recent clipboard messages, one per line. */
    synchronized String clipboardLog() {
        return String.join("\n", clipEvents);
    }

    /** Logs the start of a cliprdr message (its first chunk) in an MCS data PDU. */
    private void clipEvent(String direction, byte[] data, int offset, int length, int mcsChoice) {
        int end = offset + length;
        int mcs = offset + 7;
        if (cliprdrChannel < 0 || mcs + 7 > end || (data[mcs] & 0xFF) != mcsChoice) {
            return;
        }
        int channel = ((data[mcs + 3] & 0xFF) << 8) | (data[mcs + 4] & 0xFF);
        int userData = mcs + 7 + (((data[mcs + 6] & 0xFF) & 0x80) != 0 ? 1 : 0);
        if (channel != cliprdrChannel || userData + 16 > end) {
            return;
        }
        long total = le32(data, userData);
        long chunkFlags = le32(data, userData + 4);
        if ((chunkFlags & 0x1) == 0) {
            return;
        }
        int body = userData + 8;
        int type = (data[body] & 0xFF) | ((data[body + 1] & 0xFF) << 8);
        boolean whole = (chunkFlags & 0x3) == 0x3;
        if (mcsChoice == 0x64 && whole && (type == 7 || type == 6)) {
            byte[] pdu = java.util.Arrays.copyOfRange(data, offset, end);
            if (type == 7) {
                clientCapabilities = pdu;
            } else {
                clientTempDirectory = pdu;
            }
        } else if (mcsChoice == 0x68 && type == 1) {
            monitorReadyCount += 1;
            if (monitorReadyCount > 1 && clientCapabilities != null) {
                int size = clientCapabilities.length
                    + (clientTempDirectory == null ? 0 : clientTempDirectory.length);
                pendingReplay = java.util.Arrays.copyOf(clientCapabilities, size);
                if (clientTempDirectory != null) {
                    System.arraycopy(clientTempDirectory, 0, pendingReplay,
                        clientCapabilities.length, clientTempDirectory.length);
                }
            }
        }
        int flags = (data[body + 2] & 0xFF) | ((data[body + 3] & 0xFF) << 8);
        long dataLength = le32(data, body + 4);
        StringBuilder line = new StringBuilder(String.format(
            Locale.US, "%tT.%<tL %s %s", System.currentTimeMillis(), direction, clipTypeName(type)));
        if (flags == 1) {
            line.append(" OK");
        } else if (flags == 2) {
            line.append(" FAIL");
        }
        int field = body + 8;
        if ((type == 4 || type == 10 || type == 11) && field + 4 <= end) {
            line.append(type == 4 ? " format=0x" : " id=").append(
                type == 4 ? Long.toHexString(le32(data, field)) : String.valueOf(le32(data, field)));
        } else if (type == 8 && field + 28 <= end) {
            line.append(String.format(Locale.US, " stream=%d file=%d flags=%d pos=%d size=%d",
                le32(data, field), le32(data, field + 4), le32(data, field + 8),
                le32(data, field + 12), le32(data, field + 20)));
        } else if (type == 9 && field + 4 <= end) {
            line.append(" stream=").append(le32(data, field));
        } else if (type == 5 && dataLength == 4 && field + 4 <= end) {
            // A 4-byte answer is the Preferred DropEffect value (1 = copy).
            line.append(" value=").append(le32(data, field));
        } else if (type == 7 && field + 16 <= end) {
            line.append(" flags=0x").append(Long.toHexString(le32(data, field + 12)));
        } else if (type == 2 && dataLength > 0) {
            line.append(" formats=").append(formatNames(data, field, (int) Math.min(end, field + dataLength)));
        }
        line.append(" len=").append(dataLength);
        if (total > 1600) {
            line.append(" (").append(total).append(" B chunked)");
        }
        clipEvents.addLast(line.toString());
        while (clipEvents.size() > MAX_CLIP_EVENTS) {
            clipEvents.removeFirst();
        }
    }

    /** Ids and names in a long-format-name Format List. */
    private static String formatNames(byte[] data, int start, int end) {
        StringBuilder names = new StringBuilder();
        int at = start;
        while (at + 4 < end && names.length() < 160) {
            long id = le32(data, at);
            at += 4;
            StringBuilder name = new StringBuilder();
            while (at + 1 < end) {
                char c = (char) ((data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8));
                at += 2;
                if (c == 0) {
                    break;
                }
                name.append(c);
            }
            names.append(names.length() == 0 ? "" : ",").append(Long.toHexString(id));
            if (name.length() > 0) {
                names.append(':').append(name);
            }
        }
        return names.toString();
    }

    private static String clipTypeName(int type) {
        switch (type) {
            case 1: return "MonitorReady";
            case 2: return "FormatList";
            case 3: return "FormatListResponse";
            case 4: return "FormatDataRequest";
            case 5: return "FormatDataResponse";
            case 6: return "TempDirectory";
            case 7: return "Capabilities";
            case 8: return "FileContentsRequest";
            case 9: return "FileContentsResponse";
            case 10: return "Lock";
            case 11: return "Unlock";
            default: return "type" + type;
        }
    }

    private static int pduLength(byte[] data, int offset) {
        int first = data[offset] & 0xFF;
        if (first == 3) {
            if (offset + 4 > data.length) {
                return Integer.MAX_VALUE / 2;
            }
            return ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        }
        if ((first & 0x03) == 0) {
            if (offset + 2 > data.length) {
                return Integer.MAX_VALUE / 2;
            }
            int second = data[offset + 1] & 0xFF;
            if ((second & 0x80) == 0) {
                return second;
            }
            if (offset + 3 > data.length) {
                return Integer.MAX_VALUE / 2;
            }
            return ((second & 0x7F) << 8) | (data[offset + 2] & 0xFF);
        }
        return -1;
    }

    private void slowPath(byte[] data, int offset, int length) {
        // TPKT (4) + X.224 data TPDU (3) + MCS Send Data Request.
        int mcs = offset + 7;
        if (length < 16 || (data[mcs] & 0xFF) != 0x64) {
            return;
        }
        int channel = ((data[mcs + 3] & 0xFF) << 8) | (data[mcs + 4] & 0xFF);
        int lengthByte = data[mcs + 6] & 0xFF;
        int userData = mcs + 7;
        if ((lengthByte & 0x80) != 0) {
            userData += 1;
        }
        if (channel == IO_CHANNEL_GUESS || userData + 8 > offset + length) {
            return;
        }
        long total = le32(data, userData);
        long flags = le32(data, userData + 4);
        int size = offset + length - (userData + 8);
        boolean first = (flags & 0x1) != 0;
        boolean last = (flags & 0x2) != 0;
        if (first) {
            if (chunkChannel >= 0) {
                add("interrupted " + describeChunks());
            }
            chunkChannel = channel;
            chunkTotal = total;
            chunkSum = 0;
            chunkCount = 0;
            chunkMaxSize = 0;
            chunkFlags = "";
        }
        if (chunkChannel != channel) {
            add(String.format(Locale.US, "ch%d stray chunk flags=%x len=%d size=%d", channel, flags, total, size));
            return;
        }
        chunkSum += size;
        chunkCount += 1;
        chunkMaxSize = Math.max(chunkMaxSize, size);
        if (chunkCount <= 2 || last) {
            chunkFlags += (chunkFlags.isEmpty() ? "" : ",") + Long.toHexString(flags);
        }
        if (total != chunkTotal) {
            chunkFlags += ",len!=" + total;
        }
        if (last) {
            if (chunkTotal > 1600 || chunkCount > 1) {
                add(describeChunks());
            }
            chunkChannel = -1;
        }
    }

    /**
     * Index of the named channel in the client's GCC network data (CS_NET,
     * type 0xC003: count, then 12-byte CHANNEL_DEFs), or -1.
     */
    static int findClientChannel(byte[] data, String name) {
        for (int at = 0; at + 12 <= data.length; at++) {
            if ((data[at] & 0xFF) != 0x03 || (data[at + 1] & 0xFF) != 0xC0) {
                continue;
            }
            int blockLength = (data[at + 2] & 0xFF) | ((data[at + 3] & 0xFF) << 8);
            long count = le32(data, at + 4);
            if (count < 1 || count > 31 || blockLength != 8 + 12 * count || at + blockLength > data.length) {
                continue;
            }
            for (int index = 0; index < count; index++) {
                int def = at + 8 + 12 * index;
                String channel = new String(data, def, 8, java.nio.charset.StandardCharsets.US_ASCII)
                    .replace('\0', ' ').trim();
                if (channel.equalsIgnoreCase(name)) {
                    return index;
                }
            }
            return -1;
        }
        return -1;
    }

    /**
     * Channel id the server assigned to the client's channel at {@code index}
     * (SC_NET, type 0x0C03: MCS channel id, count, then the ids), or -1.
     */
    static int findServerChannelId(byte[] data, int count, int index) {
        for (int at = 0; at + 8 <= count; at++) {
            if ((data[at] & 0xFF) != 0x03 || (data[at + 1] & 0xFF) != 0x0C) {
                continue;
            }
            int ioChannel = (data[at + 4] & 0xFF) | ((data[at + 5] & 0xFF) << 8);
            int channels = (data[at + 6] & 0xFF) | ((data[at + 7] & 0xFF) << 8);
            if (ioChannel != IO_CHANNEL_GUESS || channels < 1 || channels > 31 || index >= channels) {
                continue;
            }
            int idAt = at + 8 + 2 * index;
            if (idAt + 2 > count) {
                return -1;
            }
            return (data[idAt] & 0xFF) | ((data[idAt + 1] & 0xFF) << 8);
        }
        return -1;
    }

    private String describeChunks() {
        return String.format(
            Locale.US,
            "ch%d msg %d B in %d chunks (max %d, sum %d, flags %s)",
            chunkChannel, chunkTotal, chunkCount, chunkMaxSize, chunkSum, chunkFlags
        );
    }

    private void add(String entry) {
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    private static long le32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
            | ((data[offset + 1] & 0xFFL) << 8)
            | ((data[offset + 2] & 0xFFL) << 16)
            | ((data[offset + 3] & 0xFFL) << 24);
    }
}

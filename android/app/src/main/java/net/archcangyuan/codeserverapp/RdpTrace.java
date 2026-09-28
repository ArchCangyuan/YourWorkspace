package net.archcangyuan.codeserverapp;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Watches one relayed RDP session. It records, for diagnosing disconnects, the
 * virtual channel messages the client sent (with their chunk headers) and the
 * last bytes the server sent before the connection ended.
 *
 * It also repairs one thing in the client's stream: IronRDP marks every
 * clipboard chunk CHANNEL_FLAG_SHOW_PROTOCOL although it declares the cliprdr
 * channel without CHANNEL_OPTION_SHOW_PROTOCOL. Windows then hands chunked
 * clipboard messages over unassembled and drops the connection, so a file
 * paste over one chunk (1600 bytes) fails. The flag is cleared on the chunks
 * of multi-chunk cliprdr messages.
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
    private int repairedChunks;

    // The virtual channel message being chunked, if any.
    private int chunkChannel = -1;
    private long chunkTotal;
    private long chunkSum;
    private int chunkCount;
    private int chunkMaxSize;
    private String chunkFlags = "";

    /** Records (and repairs, in place) a message the client sends to the server. */
    synchronized void fromClient(byte[] message) {
        bytesUp += message.length;
        if (cliprdrIndex < 0) {
            cliprdrIndex = findClientChannel(message, "cliprdr");
        }
        if (pending.length > 0) {
            // A PDU split across messages: record it, but repair only whole PDUs.
            byte[] joined = new byte[pending.length + message.length];
            System.arraycopy(pending, 0, joined, 0, pending.length);
            System.arraycopy(message, 0, joined, pending.length, message.length);
            parse(joined, pending.length, message);
            return;
        }
        parse(message, 0, message);
    }

    private void parse(byte[] data, int messageStart, byte[] message) {
        int offset = 0;
        while (offset < data.length) {
            int length = pduLength(data, offset);
            if (length <= 0) {
                pending = new byte[0];
                return;
            }
            // Repair as soon as the chunk header is here: the rest of the PDU
            // may only come with the next message, after this one is sent.
            if ((data[offset] & 0xFF) == 3 && offset + 4 <= data.length) {
                int flagsAt = repair(data, offset, Math.min(data.length, offset + length));
                if (flagsAt >= messageStart) {
                    message[flagsAt - messageStart] = data[flagsAt];
                }
            }
            if (offset + length > data.length) {
                break;
            }
            if ((data[offset] & 0xFF) == 3) {
                slowPath(data, offset, length);
            }
            offset += length;
        }
        pending = java.util.Arrays.copyOfRange(data, offset, data.length);
    }

    /**
     * Clears SHOW_PROTOCOL on a chunk of a multi-chunk cliprdr message whose
     * header lies in {@code data[offset, end)}. Returns the flag byte's index if
     * it changed it, or -1.
     */
    private int repair(byte[] data, int offset, int end) {
        int mcs = offset + 7;
        if (cliprdrChannel < 0 || mcs + 7 > end || (data[mcs] & 0xFF) != 0x64) {
            return -1;
        }
        int channel = ((data[mcs + 3] & 0xFF) << 8) | (data[mcs + 4] & 0xFF);
        int userData = mcs + 7 + (((data[mcs + 6] & 0xFF) & 0x80) != 0 ? 1 : 0);
        int flagsAt = userData + 4;
        if (channel != cliprdrChannel || flagsAt >= end) {
            return -1;
        }
        int flags = data[flagsAt] & 0xFF;
        boolean chunked = (flags & 0x03) != 0x03;
        if (!chunked || (flags & 0x10) == 0) {
            return -1;
        }
        data[flagsAt] = (byte) (flags & ~0x10);
        repairedChunks += 1;
        return flagsAt;
    }

    synchronized void fromServer(byte[] data, int count) {
        bytesDown += count;
        if (cliprdrChannel < 0 && cliprdrIndex >= 0) {
            cliprdrChannel = findServerChannelId(data, count, cliprdrIndex);
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
            .append(", repaired ").append(repairedChunks).append(" chunks");
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

    /** Returns the index of a flag byte it changed, or -1. */
    private int slowPath(byte[] data, int offset, int length) {
        // TPKT (4) + X.224 data TPDU (3) + MCS Send Data Request.
        int mcs = offset + 7;
        if (length < 16 || (data[mcs] & 0xFF) != 0x64) {
            return -1;
        }
        int channel = ((data[mcs + 3] & 0xFF) << 8) | (data[mcs + 4] & 0xFF);
        int lengthByte = data[mcs + 6] & 0xFF;
        int userData = mcs + 7;
        if ((lengthByte & 0x80) != 0) {
            userData += 1;
        }
        if (channel == IO_CHANNEL_GUESS || userData + 8 > offset + length) {
            return -1;
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
        int changed = -1;
        if (chunkChannel != channel) {
            add(String.format(Locale.US, "ch%d stray chunk flags=%x len=%d size=%d", channel, flags, total, size));
            return changed;
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
        return changed;
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

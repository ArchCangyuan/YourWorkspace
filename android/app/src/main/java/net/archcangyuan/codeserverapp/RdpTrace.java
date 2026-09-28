package net.archcangyuan.codeserverapp;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * A small record of one relayed RDP session for diagnosing disconnects: the
 * virtual channel messages the client sent (with their chunk headers) and the
 * last bytes the server sent before the connection ended.
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

    // The virtual channel message being chunked, if any.
    private int chunkChannel = -1;
    private long chunkTotal;
    private long chunkSum;
    private int chunkCount;
    private int chunkMaxSize;
    private String chunkFlags = "";

    synchronized void fromClient(byte[] message) {
        bytesUp += message.length;
        byte[] data = message;
        if (pending.length > 0) {
            data = new byte[pending.length + message.length];
            System.arraycopy(pending, 0, data, 0, pending.length);
            System.arraycopy(message, 0, data, pending.length, message.length);
        }
        int offset = 0;
        while (offset < data.length) {
            int length = pduLength(data, offset);
            if (length <= 0) {
                // Not a PDU start we understand: stop parsing this stream.
                pending = new byte[0];
                return;
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

    synchronized void fromServer(byte[] data, int count) {
        bytesDown += count;
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
        text.append("up ").append(bytesUp).append(" B, down ").append(bytesDown).append(" B");
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

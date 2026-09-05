package io.flowcatalyst.platform.auth.mfa;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/// The enrolment QR (`docs/spec/auth-identity.md` §6.5): a square PNG as a
/// `data:image/png;base64,…` URI. The PNG is written by hand (one
/// 1-bit-per-pixel greyscale IDAT) so the server never needs `java.desktop`,
/// which keeps the native image small and the headless JVM honest.
/// Best-effort: a render failure yields empty, never an error.
public final class QrPng {

    public static final int SIZE = 240;

    private QrPng() {
    }

    public static Optional<String> dataUri(String content) {
        return dataUri(content, SIZE);
    }

    public static Optional<String> dataUri(String content, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
            return Optional.of("data:image/png;base64," + Base64.getEncoder().encodeToString(png(m)));
        } catch (WriterException | IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /// Greyscale, bit depth 1: a set module is black (0), the rest white (1).
    static byte[] png(BitMatrix m) throws IOException {
        int w = m.getWidth();
        int h = m.getHeight();
        int rowBytes = (w + 7) / 8;
        var raw = new ByteArrayOutputStream((rowBytes + 1) * h);
        for (int y = 0; y < h; y++) {
            raw.write(0); // filter: none
            int acc = 0;
            int bits = 0;
            for (int x = 0; x < w; x++) {
                acc = (acc << 1) | (m.get(x, y) ? 0 : 1);
                bits++;
                if (bits == 8) {
                    raw.write(acc);
                    acc = 0;
                    bits = 0;
                }
            }
            if (bits > 0) {
                raw.write(acc << (8 - bits));
            }
        }
        var idat = new ByteArrayOutputStream();
        try (var z = new DeflaterOutputStream(idat, new Deflater(Deflater.BEST_COMPRESSION))) {
            z.write(raw.toByteArray());
        }
        var out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        var ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, w);
        writeInt(ihdr, h);
        ihdr.write(1); // bit depth
        ihdr.write(0); // colour type: greyscale
        ihdr.write(0); // compression
        ihdr.write(0); // filter
        ihdr.write(0); // interlace
        chunk(out, "IHDR", ihdr.toByteArray());
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        writeInt(out, data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        var crc = new CRC32();
        crc.update(t);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}

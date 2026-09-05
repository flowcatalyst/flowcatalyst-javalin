package io.flowcatalyst.platform.auth.mfa;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class QrPngTest {

    @Test
    void rendersA240PixelGreyscalePngDataUri() {
        String uri = QrPng.dataUri("otpauth://totp/FlowCatalyst:user@example.com?secret=ABC234").orElseThrow();
        assertThat(uri).startsWith("data:image/png;base64,");
        byte[] png = Base64.getDecoder().decode(uri.substring("data:image/png;base64,".length()));
        assertThat(png).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteBuffer ihdr = ByteBuffer.wrap(png, 16, 13);
        assertThat(ihdr.getInt()).as("width").isEqualTo(240);
        assertThat(ihdr.getInt()).as("height").isEqualTo(240);
        assertThat(ihdr.get()).as("bit depth").isEqualTo((byte) 1);
        assertThat(ihdr.get()).as("greyscale").isEqualTo((byte) 0);
        assertThat(new String(png, png.length - 8, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("IEND");
    }

    @Test
    void differentContentRendersDifferentPixels() {
        assertThat(QrPng.dataUri("a")).isNotEqualTo(QrPng.dataUri("b"));
    }

    @Test
    void anUnrenderableInputIsEmptyNotAnError() {
        assertThat(QrPng.dataUri("")).isEmpty();
    }
}

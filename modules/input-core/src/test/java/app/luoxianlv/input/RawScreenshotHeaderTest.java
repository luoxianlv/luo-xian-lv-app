package app.luoxianlv.input;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class RawScreenshotHeaderTest {
  @Test public void readsFourFieldsWithoutStride() throws Exception {
    byte[] header = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        .putInt(2560).putInt(1600).putInt(1).putInt(1).array();
    var value = RawScreenshotHeader.read(new ByteArrayInputStream(header));
    assertEquals(2560, value.width); assertEquals(1600, value.height);
    assertEquals(4, value.bytesPerPixel);
  }
  @Test public void rgbaAndBgraKeepAlphaAndChannels() throws Exception {
    int[] out = new int[1];
    new RawScreenshotHeader(2, 2, 1, 1).decode(new byte[] {11,22,33,44}, out, 1);
    assertEquals(0x2c4080bf, out[0]);
    new RawScreenshotHeader(2, 2, 5, 1).decode(new byte[] {33,22,11,44}, out, 1);
    assertEquals(0x2c4080bf, out[0]);
    new RawScreenshotHeader(2, 2, 2, 1).decode(new byte[] {11,22,33,0}, out, 1);
    assertEquals(0xff0b1621, out[0]);
  }
  @Test public void transparentAndOpaquePixelsAvoidExtraPremultiplication() throws Exception {
    int[] out = new int[3];
    new RawScreenshotHeader(3, 2, 1, 1).decode(new byte[] {
        7,8,9,0, 11,22,33,(byte)255, (byte)128,(byte)128,(byte)128,(byte)128}, out, 3);
    assertArrayEquals(new int[] {0,0xff0b1621,0x80ffffff}, out);
  }
  @Test public void rgb565ExpandsToFullChannels() throws Exception {
    int[] out = new int[3];
    new RawScreenshotHeader(3, 2, 4, 1).decode(new byte[] {0,(byte)248,(byte)224,7,31,0}, out, 3);
    assertArrayEquals(new int[] {0xffff0000,0xff00ff00,0xff0000ff}, out);
  }
  @Test public void rgb888UsesTightlyPackedPixels() throws Exception {
    int[] out = new int[2];
    new RawScreenshotHeader(2, 2, 3, 1).decode(new byte[] {1,2,3,4,5,6}, out, 2);
    assertArrayEquals(new int[] {0xff010203,0xff040506}, out);
  }
  @Test public void rejectsTruncatedAndUnsafeFramesBeforeAllocation() {
    assertThrows(IOException.class, () -> RawScreenshotHeader.read(new ByteArrayInputStream(new byte[12])));
    assertThrows(IOException.class, () -> new RawScreenshotHeader(-1,2,1,1));
    assertThrows(IOException.class, () -> new RawScreenshotHeader(8192,8192,1,1));
    assertThrows(IOException.class, () -> RawScreenshotHeader.readFully(new ByteArrayInputStream(new byte[1]),new byte[4],4));
  }
  @Test public void wideColorAndUnknownFormatsRequireCompatibleDecoder() {
    assertThrows(RawScreenshotHeader.Unsupported.class, () -> new RawScreenshotHeader(2,2,1,0));
    assertThrows(RawScreenshotHeader.Unsupported.class, () -> new RawScreenshotHeader(2,2,1,2));
    assertThrows(RawScreenshotHeader.Unsupported.class, () -> new RawScreenshotHeader(2,2,99,1));
  }
}

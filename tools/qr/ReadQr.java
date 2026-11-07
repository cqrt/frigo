// Reads the pairing code out of a picture of the television, so a pairing can
// be tried from a computer with no phone at hand: take a screenshot with
// `adb exec-out screencap -p > tv.png`, then
//
//   java -cp <zxing core.jar> tools/qr/ReadQr.java tv.png
//
// The ZXing jar is the one Gradle already fetched, under
// ~/.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3/.
//
// Nothing in either app uses this. It is the same decoder the phone runs, with
// the file standing in for the camera.

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class ReadQr {

    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(new File(args[0]));
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] luminance = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                luminance[y * width + x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
            }
        }
        var source = new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false);
        System.out.println(
            new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText()
        );
    }
}

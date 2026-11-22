// Makes the icon of the Windows application (an .ico) from the logo, as it is: the icons of Windows have no shape of their own to fit in, and the
// logo is drawn on a transparent background. An .ico holds one image for each size that Windows draws (the taskbar, the Start menu, File Explorer
// at its sizes, at 100 to 200 % of scaling), each a PNG, which Windows reads since Vista.
// Run by the build with the java of its toolchain, in a process of its own: `java -Djava.awt.headless=true make-ico.java <logo.png> <KaTTY.ico>`.
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

class MakeIco {
    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 96, 128, 256};

    public static void main(String[] args) throws IOException {
        BufferedImage logo = ImageIO.read(new File(args[0]));
        List<byte[]> images = new ArrayList<>();
        for (int size : SIZES) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(scaled(logo, size), "png", png);
            images.add(png.toByteArray());
        }

        // ICONDIR (6 bytes), then an ICONDIRENTRY (16 bytes) for each image, then the images.
        int offset = 6 + 16 * SIZES.length;
        int total = offset + images.stream().mapToInt(image -> image.length).sum();
        ByteBuffer ico = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        for (int i = 0; i < SIZES.length; i++) {
            // A width or a height of 256 is written as 0.
            ico.put((byte) (SIZES[i] % 256)).put((byte) (SIZES[i] % 256));
            ico.put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 32);
            ico.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        images.forEach(ico::put);
        File out = new File(args[1]);
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), ico.array());
    }

    // Halved again and again before the last step: one bicubic step from 612 to 16 pixels skips most of them, and the small sizes come out ragged.
    static BufferedImage scaled(BufferedImage image, int size) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= size) current = draw(current, current.getWidth() / 2);
        return draw(current, size);
    }

    static BufferedImage draw(BufferedImage image, int size) {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(image, 0, 0, size, size, null);
        graphics.dispose();
        return scaled;
    }
}

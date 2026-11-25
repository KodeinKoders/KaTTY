// Draws the icon of the application: the logo on a rounded square of one colour. It is the shape that macOS wants the icon of an application to have:
// since macOS 26, it shrinks an icon of another shape, such as the bare logo, and puts it on a grey rounded square. The square is the one of the grid of
// the icons of macOS: 824 pixels, with corners of a radius of 185.4, in the middle of a canvas of 1024 that is transparent around it, but for the
// shadow that the square casts, as the icons of the other applications do.
// Run by the build with the java of its toolchain, in a process of its own: `java -Djava.awt.headless=true make-icon.java <logo.png> <#rrggbb> <icon.png>`.
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

class MakeIcon {
    static final int CANVAS = 1024;
    static final double SQUARE = 824;
    static final double RADIUS = 185.4;
    // The logo's own canvas, which has a margin around the cat, is drawn at this part of the square: the cat's head takes four fifths of its height.
    static final double LOGO = 1.0;
    // The shadow that macOS draws under the icons of the other applications in the Dock, at 46 points: black at 28 %, 21 pixels down (of the 1024),
    // blurred over 40 (the standard deviation of a Gaussian blur), measured on the icon of Visual Studio Code. macOS draws it about 2 points wide at
    // any size, so it is relatively smaller in a bigger icon: the Dock is where this image is shown as it is (see `appIcon` in `build.gradle.kts`).
    static final float SHADOW_OPACITY = 0.28f;
    static final double SHADOW_OFFSET = 21;
    static final double SHADOW_BLUR = 40;

    public static void main(String[] args) throws IOException {
        BufferedImage logo = ImageIO.read(new File(args[0]));
        Color background = Color.decode(args[1]);
        double inset = (CANVAS - SQUARE) / 2;
        Shape square = new RoundRectangle2D.Double(inset, inset, SQUARE, SQUARE, RADIUS * 2, RADIUS * 2);

        BufferedImage icon = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = smooth(icon.createGraphics());
        // The shadow is blurred on a bigger canvas, since the blur leaves a band as wide as its reach along the edges blank.
        graphics.drawImage(shadow(square), -reach(), -reach(), null);
        graphics.setColor(background);
        graphics.fill(square);
        int size = (int) Math.round(SQUARE * LOGO);
        int at = (CANVAS - size) / 2;
        graphics.drawImage(logo, at, at, size, size, null);
        graphics.dispose();
        ImageIO.write(icon, "png", new File(args[2]));
    }

    static Graphics2D smooth(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return graphics;
    }

    // How far the blur reaches: three standard deviations, beyond which a Gaussian is nothing.
    static int reach() {
        return (int) Math.ceil(SHADOW_BLUR * 3);
    }

    // The square, moved down, in translucent black, blurred: a Gaussian blur along each axis in turn, which is the same as both at once. The image
    // is the canvas with a margin of the blur's reach all around. The blur is done here and not by `ConvolveOp`, whose native code (`mlib_image`)
    // crashed the JVM once on CI's Windows ARM64: the image is black, so only its alpha is blurred.
    static BufferedImage shadow(Shape square) {
        int reach = reach();
        BufferedImage shadow = new BufferedImage(CANVAS + 2 * reach, CANVAS + 2 * reach, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = smooth(shadow.createGraphics());
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, SHADOW_OPACITY));
        graphics.setColor(Color.BLACK);
        graphics.fill(AffineTransform.getTranslateInstance(reach, reach + SHADOW_OFFSET).createTransformedShape(square));
        graphics.dispose();
        int width = shadow.getWidth();
        int height = shadow.getHeight();
        WritableRaster raster = shadow.getRaster();
        // The fourth band of an ARGB image is its alpha.
        float[] alpha = raster.getSamples(0, 0, width, height, 3, (float[]) null);
        float[] weights = gaussian(SHADOW_BLUR);
        float[] across = blur(alpha, width, height, weights, 1, width);
        float[] blurred = blur(across, width, height, weights, width, 1);
        int[] samples = new int[blurred.length];
        for (int i = 0; i < blurred.length; i++) samples[i] = Math.min(255, Math.round(blurred[i]));
        // Black, premultiplied: its colours are 0 whatever its alpha.
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        result.getRaster().setSamples(0, 0, width, height, 3, samples);
        return result;
    }

    // Blurs the values of an image along one axis: `step` is the distance in the array from a pixel to the next along it, `line` from a line of the
    // axis to the next. What is outside the image counts as 0.
    static float[] blur(float[] values, int width, int height, float[] weights, int step, int line) {
        int radius = weights.length / 2;
        int length = step == 1 ? width : height;
        int lines = step == 1 ? height : width;
        float[] result = new float[values.length];
        for (int l = 0; l < lines; l++) {
            int start = l * line;
            for (int i = 0; i < length; i++) {
                float sum = 0;
                int from = Math.max(0, i - radius);
                int to = Math.min(length - 1, i + radius);
                for (int j = from; j <= to; j++) sum += weights[j - i + radius] * values[start + j * step];
                result[start + i * step] = sum;
            }
        }
        return result;
    }

    // The weights of a Gaussian of this standard deviation, out to its reach, which add up to 1.
    static float[] gaussian(double sigma) {
        int radius = reach();
        float[] weights = new float[radius * 2 + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) {
            weights[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
            sum += weights[i + radius];
        }
        for (int i = 0; i < weights.length; i++) weights[i] /= sum;
        return weights;
    }
}

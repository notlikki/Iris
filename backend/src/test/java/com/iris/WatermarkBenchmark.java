package com.iris;

import com.iris.stego.SteganographyV2;
import com.iris.stego.BenchmarkBaselineRecovery;
import com.iris.stego.ImageAlignment;
import com.iris.stego.SteganographyV3;
import com.iris.stego.WatermarkRecovery;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Offline, reproducible benchmark. Run after test-compile; no downloads during tests. */
public final class WatermarkBenchmark {
    private static final byte[] TOKEN = HexFormat.of().parseHex("763ecde945ab87d1b04a629f");
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args.length == 0 ? "target/watermark-benchmark.csv" : args[0]);
        if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output))) {
            csv.println("version,image,attack,recovered,psnr_db,elapsed_ms");
            for (var fixture : fixtures().entrySet()) {
                BufferedImage source = fixture.getValue();
                for (int version : new int[]{2, 3}) {
                    if (args.length > 1 && version != Integer.parseInt(args[1])) continue;
                    BufferedImage signed = version == 2 ? SteganographyV2.embed(source, TOKEN).image()
                            : SteganographyV3.embed(source, TOKEN).image();
                    double quality = psnr(source, signed);
                    for (var attack : attacks(signed).entrySet()) {
                        long start = System.nanoTime();
                        boolean recovered = recover(version, attack.getValue(), source, TOKEN);
                        double ms = (System.nanoTime() - start) / 1_000_000.0;
                        csv.printf(Locale.ROOT, "%d,%s,%s,%s,%.3f,%.3f%n", version, fixture.getKey(), attack.getKey(), recovered, quality, ms);
                        csv.flush();
                    }
                    for (String negative : new String[]{"unsigned", "different-key"}) {
                        BufferedImage image = negative.equals("unsigned") ? source
                                : (version == 2 ? SteganographyV2.embed(source, new byte[12]).image()
                                : SteganographyV3.embed(source, new byte[12]).image());
                        long start = System.nanoTime();
                        boolean accepted = recover(version, image, source, TOKEN);
                        csv.printf(Locale.ROOT, "%d,%s,%s,%s,%.3f,%.3f%n", version, fixture.getKey(), negative,
                                accepted, quality, (System.nanoTime() - start) / 1_000_000.0);
                        csv.flush();
                    }
                    System.out.printf(Locale.ROOT, "Completed v%d %s (%.1f dB)%n", version, fixture.getKey(), quality);
                }
            }
        }
    }

    static Map<String, BufferedImage> fixtures() throws IOException {
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        for (String name : new String[]{"astronaut", "camera", "coffee", "chelsea"}) {
            try (InputStream stream = WatermarkBenchmark.class.getResourceAsStream("/watermark/" + name + ".png")) {
                if (stream == null) throw new IOException("Missing benchmark fixture " + name);
                images.put(name, rgb(ImageIO.read(stream)));
            }
        }
        for (String filename : new String[]{"rocket.jpg", "brick.png"}) {
            try (InputStream stream = WatermarkBenchmark.class.getResourceAsStream("/watermark/" + filename)) {
                if (stream == null) throw new IOException("Missing holdout fixture " + filename);
                images.put(filename.substring(0, filename.indexOf('.')), rgb(ImageIO.read(stream)));
            }
        }
        BufferedImage smooth = new BufferedImage(512, 384, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = smooth.createGraphics();
        graphics.setPaint(new GradientPaint(0, 0, new Color(40, 90, 130), 512, 384, new Color(200, 190, 160)));
        graphics.fillRect(0, 0, 512, 384); graphics.dispose();
        images.put("smooth", smooth);
        BufferedImage screenshot = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        graphics = screenshot.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 640, 480);
        graphics.setColor(new Color(30, 40, 55)); graphics.fillRect(0, 0, 640, 55);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        for (int row = 0; row < 16; row++) graphics.drawString("IRIS record " + row + " — Image verification", 25, 85 + row * 23);
        graphics.dispose(); images.put("screenshot", screenshot);
        return images;
    }

    static Map<String, BufferedImage> attacks(BufferedImage image) throws IOException {
        int w = image.getWidth(), h = image.getHeight();
        Map<String, BufferedImage> attacks = new LinkedHashMap<>();
        attacks.put("unchanged", image);
        attacks.put("jpeg35", jpeg(image, .35f));
        attacks.put("jpeg10", jpeg(image, .10f));
        attacks.put("half", resize(image, w / 2, h / 2));
        BufferedImage crop = image.getSubimage(w / 8 + 3, h / 8 + 1, w * 3 / 4, h * 3 / 4);
        attacks.put("crop", crop);
        attacks.put("crop-half-jpeg", jpeg(resize(crop, crop.getWidth() / 2, crop.getHeight() / 2), .35f));
        attacks.put("rotate90", rotate(image));
        attacks.put("rotate5", ImageAlignment.rotate(image, 5));
        attacks.put("blur3", new ConvolveOp(new Kernel(3, 3, new float[]{1/9f,1/9f,1/9f,1/9f,1/9f,1/9f,1/9f,1/9f,1/9f}),
                ConvolveOp.EDGE_NO_OP, null).filter(image, null));
        BufferedImage painted = rgb(image);
        Graphics2D graphics = painted.createGraphics(); graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(w / 4, h / 4, w / 3, h / 3); graphics.dispose();
        attacks.put("painted", painted);
        return attacks;
    }

    static boolean recover(int version, BufferedImage image, BufferedImage source, byte[] expected) {
        if (version == 2) {
            var nativeResult = SteganographyV2.extractRobust(image);
            if (nativeResult != null && Arrays.equals(expected, nativeResult.token())) return true;
            return BenchmarkBaselineRecovery.recover(image, List.of(new BenchmarkBaselineRecovery.Size(source.getWidth(), source.getHeight())),
                    SteganographyV2::extractRobust, value -> Arrays.equals(expected, value.token())) != null;
        }
        var nativeResult = SteganographyV3.extractRobust(image);
        if (nativeResult != null && Arrays.equals(expected, nativeResult.token())) return true;
        if (WatermarkRecovery.recover(image, List.of(new WatermarkRecovery.Size(source.getWidth(), source.getHeight())),
                SteganographyV3::extractRobust, value -> Arrays.equals(expected, value.token())) != null) return true;
        ImageAlignment.Estimate alignment = ImageAlignment.align(image, source);
        if (alignment == null) return false;
        var aligned = SteganographyV3.extractRobust(alignment.image());
        return aligned != null && Arrays.equals(expected, aligned.token());
    }

    static double psnr(BufferedImage a, BufferedImage b) {
        double error = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            int first = a.getRGB(x, y), second = b.getRGB(x, y);
            for (int shift : new int[]{0, 8, 16}) {
                int difference = ((first >> shift) & 255) - ((second >> shift) & 255);
                error += difference * difference;
            }
        }
        return error == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255 * 3 * a.getWidth() * a.getHeight() / error);
    }
    static BufferedImage rotate(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            result.setRGB(source.getHeight() - y - 1, x, source.getRGB(x, y));
        return result;
    }
    static BufferedImage rgb(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics(); graphics.drawImage(image, 0, 0, null); graphics.dispose(); return result;
    }
    static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics(); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(image, 0, 0, width, height, null); graphics.dispose(); return result;
    }
    static BufferedImage jpeg(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream); var params = writer.getDefaultWriteParam();
            params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT); params.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally { writer.dispose(); }
        return ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
    }
}

// Generates the application icons: java -Djava.awt.headless=true app/src/packaging/IconGen.java app/src/packaging
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Draws the app icon and writes PNG, ICO (PNG-compressed entries) and ICNS files. */
public class IconGen {
    static BufferedImage draw(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double m = s * 0.06, w = s - 2 * m, r = s * 0.22;
        g.setPaint(new GradientPaint(0, 0, new Color(0x1E88E5), 0, s, new Color(0x0D47A1)));
        g.fill(new RoundRectangle2D.Double(m, m, w, w, r, r));
        // Arrow: a message leaving, drawn as one path so the joins do not overlap.
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (s * 0.055), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double ay = s * 0.72, ax1 = s * 0.24, ax2 = s * 0.74;
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(ax1, ay);
        arrow.lineTo(ax2, ay);
        arrow.moveTo(ax2 - s * 0.1, ay - s * 0.09);
        arrow.lineTo(ax2, ay);
        arrow.lineTo(ax2 - s * 0.1, ay + s * 0.09);
        g.draw(arrow);
        // "HL7"
        g.setColor(Color.WHITE);
        Font f = new Font("DejaVu Sans", Font.BOLD, (int) (s * 0.3));
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String t = "HL7";
        g.drawString(t, (float) ((s - fm.stringWidth(t)) / 2.0), (float) (s * 0.52));
        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.write(dir.resolve("hl7-sender.png"), png(draw(512)));
        // ICO with PNG-compressed images (Windows Vista+).
        int[] icoSizes = {16, 24, 32, 48, 64, 128, 256};
        byte[][] icoData = new byte[icoSizes.length][];
        for (int i = 0; i < icoSizes.length; i++) icoData[i] = png(draw(icoSizes[i]));
        ByteArrayOutputStream ico = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ico);
        d.writeShort(0); d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) icoSizes.length));
        int offset = 6 + 16 * icoSizes.length;
        for (int i = 0; i < icoSizes.length; i++) {
            int sz = icoSizes[i];
            d.writeByte(sz >= 256 ? 0 : sz); d.writeByte(sz >= 256 ? 0 : sz); d.writeByte(0); d.writeByte(0);
            d.writeShort(Short.reverseBytes((short) 1)); d.writeShort(Short.reverseBytes((short) 32));
            d.writeInt(Integer.reverseBytes(icoData[i].length)); d.writeInt(Integer.reverseBytes(offset));
            offset += icoData[i].length;
        }
        for (byte[] b : icoData) d.write(b);
        Files.write(dir.resolve("hl7-sender.ico"), ico.toByteArray());
        // ICNS with PNG entries.
        String[] types = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};
        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream b = new DataOutputStream(body);
        for (int i = 0; i < types.length; i++) {
            byte[] p = png(draw(sizes[i]));
            b.writeBytes(types[i]); b.writeInt(p.length + 8); b.write(p);
        }
        ByteArrayOutputStream icns = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(icns);
        c.writeBytes("icns"); c.writeInt(body.size() + 8); c.write(body.toByteArray());
        Files.write(dir.resolve("hl7-sender.icns"), icns.toByteArray());
    }
}

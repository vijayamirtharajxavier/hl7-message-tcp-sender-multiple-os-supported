// Generates the application icons: java -Djava.awt.headless=true app/src/packaging/IconGen.java app/src/packaging
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/**
 * Draws the app icon and writes PNG, ICO (PNG-compressed entries) and ICNS files.
 *
 * <p>The design: an HL7 v2 message card (three segments, each a row of fields split by pipe gaps, with the
 * segment name in an accent colour) leaving to the right, and a green check badge for the accepted ACK.
 * Below 48 px the card keeps two segments and the badge grows, so the shape still reads at 16 px.
 */
public class IconGen {
    static final Color BG_TOP = new Color(0x14B8A6);
    static final Color BG_BOTTOM = new Color(0x1E3A8A);
    static final Color FIELD = new Color(0xCBD5E1);
    static final Color SEGMENT = new Color(0x1E3A8A);
    static final Color PIPE = new Color(0x0D9488);
    static final Color ACK_TOP = new Color(0x4ADE80);
    static final Color ACK_BOTTOM = new Color(0x16A34A);

    static BufferedImage draw(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        boolean small = s < 48;

        // Background tile: diagonal teal to indigo, with a soft highlight across the top.
        double m = s * 0.05, w = s - 2 * m, r = s * 0.23;
        RoundRectangle2D tile = new RoundRectangle2D.Double(m, m, w, w, r, r);
        g.setPaint(new GradientPaint((float) m, (float) m, BG_TOP, (float) (m + w), (float) (m + w), BG_BOTTOM));
        g.fill(tile);
        Shape oldClip = g.getClip();
        g.clip(tile);
        g.setPaint(new GradientPaint(0, (float) m, new Color(255, 255, 255, 46), 0, (float) (s * 0.5),
                new Color(255, 255, 255, 0)));
        g.fill(new Rectangle2D.Double(0, 0, s, s * 0.5));
        g.setClip(oldClip);

        // Motion lines behind the card: the message is on its way.
        if (!small) {
            g.setColor(new Color(255, 255, 255, 110));
            g.setStroke(new BasicStroke((float) (s * 0.028), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double[][] lines = {{0.12, 0.33, 0.19}, {0.09, 0.44, 0.19}, {0.12, 0.55, 0.19}};
            for (double[] l : lines) {
                g.draw(new Line2D.Double(s * l[0], s * l[1], s * l[2], s * l[1]));
            }
        }

        // The message card, with a soft shadow.
        double cx = s * (small ? 0.13 : 0.24), cy = s * (small ? 0.17 : 0.21);
        double cw = s * (small ? 0.66 : 0.56), ch = s * (small ? 0.52 : 0.46), cr = s * 0.07;
        for (int i = 4; i >= 1; i--) {
            double o = s * 0.006 * i;
            g.setColor(new Color(10, 20, 60, 18));
            g.fill(new RoundRectangle2D.Double(cx + o * 0.4, cy + o, cw, ch, cr + o, cr + o));
        }
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(cx, cy, cw, ch, cr, cr));

        // Segments: the segment name, then fields split by pipes, as in MSH|^~\&|...
        double[][] rows = small
                ? new double[][] {{0.30, 0.66}, {0.30, 0.40}}
                : new double[][] {{0.24, 0.32, 0.18}, {0.24, 0.20, 0.28}, {0.24, 0.44}};
        double rowH = s * (small ? 0.08 : 0.05);
        double padX = cw * 0.11, inner = cw - 2 * padX;
        double rowStep = small ? ch * 0.34 : ch * 0.27;
        double y0 = cy + (small ? ch * 0.22 : ch * 0.19);
        double pipeW = s * (small ? 0.0 : 0.022), pipeGap = cw * (small ? 0.08 : 0.035);
        for (int rIdx = 0; rIdx < rows.length; rIdx++) {
            double x = cx + padX, y = y0 + rIdx * rowStep;
            double[] fields = rows[rIdx];
            for (int f = 0; f < fields.length; f++) {
                double fw = inner * fields[f];
                g.setColor(f == 0 ? SEGMENT : FIELD);
                g.fill(new RoundRectangle2D.Double(x, y, fw, rowH, rowH, rowH));
                x += fw;
                if (f < fields.length - 1) {
                    if (!small) {
                        // The pipe: taller than the fields, in the accent colour.
                        double px = x + pipeGap;
                        double ph = rowH * 1.9;
                        g.setColor(PIPE);
                        g.fill(new RoundRectangle2D.Double(px, y + rowH / 2 - ph / 2, pipeW, ph, pipeW, pipeW));
                        x = px + pipeW + pipeGap;
                    } else {
                        x += pipeGap;
                    }
                }
            }
        }

        // The ACK badge: a green check with a white ring, overlapping the card's corner.
        double br = s * (small ? 0.21 : 0.15);
        double bx = s * (small ? 0.71 : 0.75), by = s * (small ? 0.71 : 0.74);
        g.setColor(Color.WHITE);
        double ring = s * (small ? 0.035 : 0.03);
        g.fill(new Ellipse2D.Double(bx - br - ring, by - br - ring, 2 * (br + ring), 2 * (br + ring)));
        g.setPaint(new GradientPaint((float) bx, (float) (by - br), ACK_TOP, (float) bx, (float) (by + br),
                ACK_BOTTOM));
        g.fill(new Ellipse2D.Double(bx - br, by - br, 2 * br, 2 * br));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (br * 0.26), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D check = new Path2D.Double();
        check.moveTo(bx - br * 0.45, by + br * 0.02);
        check.lineTo(bx - br * 0.12, by + br * 0.34);
        check.lineTo(bx + br * 0.47, by - br * 0.30);
        g.draw(check);
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

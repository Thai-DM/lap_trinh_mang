import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;

/** Rasterizes logical system fonts to BMFont atlases. No platform font binary is distributed. */
public class GenerateUiFont {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        generate(output, "body", 20, Font.PLAIN);
        generate(output, "small", 15, Font.PLAIN);
        generate(output, "heading", 32, Font.BOLD);
    }
    static void generate(Path output, String name, int size, int style) throws Exception {
        Font font = new Font(Font.SANS_SERIF, style, size);
        var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D measure = probe.createGraphics(); measure.setFont(font);
        FontMetrics metrics = measure.getFontMetrics(); int height = metrics.getHeight(), ascent = metrics.getAscent();
        var characters = new TreeSet<Integer>();
        for(int c=32;c<=126;c++) characters.add(c);
        for(int c=160;c<=591;c++) characters.add(c);
        for(int c=0x1EA0;c<=0x1EF9;c++) characters.add(c);
        for(int c : new int[]{0x2013,0x2014,0x2022,0x2026,0x2190,0x2192}) characters.add(c);
        int x=0,y=0,row=height+8;
        var records = new ArrayList<String>();
        for(int c:characters) {
            var bounds=font.createGlyphVector(measure.getFontRenderContext(),Character.toString(c)).getPixelBounds(null,0,0);
            int advance=metrics.charWidth(c), width=Math.max(1,bounds.width)+2;
            if(x+width>1024) { x=0; y+=row; }
            records.add(String.format(Locale.ROOT,"char id=%d x=%d y=%d width=%d height=%d xoffset=%d yoffset=%d xadvance=%d page=0 chnl=15",c,x+1,y+1,bounds.width,bounds.height,bounds.x,ascent+bounds.y,advance));
            x+=width;
        }
        int atlasHeight=1; while(atlasHeight<y+row) atlasHeight*=2;
        var atlas = new BufferedImage(1024,atlasHeight,BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=atlas.createGraphics(); graphics.setFont(font); graphics.setColor(Color.WHITE);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        x=0;y=0;
        for(int c:characters) {
            var bounds=font.createGlyphVector(measure.getFontRenderContext(),Character.toString(c)).getPixelBounds(null,0,0);
            int width=Math.max(1,bounds.width)+2;
            if(x+width>1024) { x=0; y+=row; }
            graphics.drawString(Character.toString(c),x+1-bounds.x,y+1-bounds.y); x+=width;
        }
        graphics.dispose();measure.dispose();
        ImageIO.write(atlas,"png",output.resolve(name+".png").toFile());
        String header="info face=\"SansSerif\" size="+size+" bold="+(style==Font.BOLD?1:0)+" italic=0 charset=\"\" unicode=1 stretchH=100 smooth=1 aa=1 padding=0,0,0,0 spacing=0,0\n"
                +"common lineHeight="+height+" base="+ascent+" scaleW=1024 scaleH="+atlasHeight+" pages=1 packed=0\n"
                +"page id=0 file=\""+name+".png\"\nchars count="+records.size()+"\n";
        Files.writeString(output.resolve(name+".fnt"),header+String.join("\n",records)+"\n",StandardCharsets.UTF_8);
        System.out.println(name+": "+characters.size()+" glyphs, 1024x"+atlasHeight);
    }
}

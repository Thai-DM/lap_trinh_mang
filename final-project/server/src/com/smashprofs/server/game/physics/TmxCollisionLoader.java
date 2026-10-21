package com.smashprofs.server.game.physics;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/** Chỉ đọc collision XML, không resolve tileset/texture, không cần Gdx hoặc OpenGL. */
public final class TmxCollisionLoader {
    public static final String ORIGINAL_MAP_RESOURCE = "1/Map1New2Remake.tmx";
    public static final String COLLISION_LAYER = "obj";
    private static final Logger LOGGER = Logger.getLogger(TmxCollisionLoader.class.getName());

    public MapCollisionDefinition loadOriginalMap() {
        return loadResource(ORIGINAL_MAP_RESOURCE);
    }

    /** Classpath resource không phụ thuộc working directory; stream được đóng sau parse. */
    public MapCollisionDefinition loadResource(String resourceName) {
        Objects.requireNonNull(resourceName, "resourceName không được null");
        try (InputStream input = TmxCollisionLoader.class.getResourceAsStream("/" + resourceName)) {
            if (input == null) {
                throw new IllegalArgumentException("Không tìm thấy TMX trên classpath: " + resourceName);
            }
            return load(input, resourceName);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Không đọc được TMX: " + resourceName, failure);
        }
    }

    /** Caller sở hữu stream truyền vào; chỉ hỗ trợ map orthogonal hữu hạn và rectangle không xoay. */
    public MapCollisionDefinition load(InputStream input, String sourceName) {
        Objects.requireNonNull(input, "input không được null");
        Objects.requireNonNull(sourceName, "sourceName không được null");
        Document document;
        try {
            document = secureParser().parse(input);
        } catch (IOException | SAXException failure) {
            throw new IllegalArgumentException("TMX XML không hợp lệ hoặc bị từ chối: " + sourceName, failure);
        }
        Element map = document.getDocumentElement();
        if (!"map".equals(map.getTagName()) || !"orthogonal".equals(map.getAttribute("orientation"))
                || !"0".equals(attribute(map, "infinite", "0"))) {
            throw new IllegalArgumentException("Chỉ hỗ trợ TMX orthogonal hữu hạn: " + sourceName);
        }
        float mapWidth = dimension(map, "width") * dimension(map, "tilewidth");
        float mapHeight = dimension(map, "height") * dimension(map, "tileheight");
        Element layer = null;
        // Tìm đúng objectgroup ở top-level như map.getLayers().get("obj") của baseline.
        var matchingLayers = map.getElementsByTagName("objectgroup");
        for (int index = 0; index < matchingLayers.getLength(); index++) {
            Element candidate = (Element) matchingLayers.item(index);
            if (COLLISION_LAYER.equals(candidate.getAttribute("name"))) {
                if (candidate.getParentNode() != map || layer != null) {
                    throw new IllegalArgumentException("Collision layer lồng group hoặc trùng tên chưa hỗ trợ");
                }
                layer = candidate;
            }
        }
        if (layer == null) {
            throw new IllegalArgumentException("Không tìm thấy collision layer '" + COLLISION_LAYER + "': " + sourceName);
        }
        for (String offset : List.of("offsetx", "offsety", "x", "y")) {
            if (number(layer, offset, 0) != 0) {
                throw new IllegalArgumentException("Collision layer offset chưa hỗ trợ: " + offset);
            }
        }
        List<MapCollisionDefinition.CollisionRectangle> rectangles = new ArrayList<>();
        for (Node node = layer.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element object)) {
                continue;
            }
            if ("properties".equals(object.getTagName())) {
                continue; // Baseline không dùng custom properties để cấu hình fixture.
            }
            if (!"object".equals(object.getTagName())) {
                throw new IllegalArgumentException("Collision element chưa hỗ trợ: " + object.getTagName());
            }
            rejectUnsupportedGeometry(object);
            int id;
            try {
                id = Integer.parseInt(object.getAttribute("id"));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Collision object cần ID hợp lệ", failure);
            }
            float x = number(object, "x", 0);
            float tmxY = number(object, "y", 0);
            float width = number(object, "width", 0);
            float height = number(object, "height", 0);
            // BaseTmxMapLoader 1.10.0: flipY=true, convertObjectToTileSpace=false.
            float y = mapHeight - tmxY - height;
            rectangles.add(new MapCollisionDefinition.CollisionRectangle(id, x, y, width, height));
            if (width == 0 || height == 0) {
                LOGGER.warning("Giữ geometry kích thước 0 của baseline, không bỏ/thêm độ dày: "
                        + sourceName + ", object ID=" + id);
            }
        }
        var lavaZones=new ArrayList<MapCollisionDefinition.CollisionRectangle>();
        for(int index=0;index<matchingLayers.getLength();index++) {
            Element candidate=(Element)matchingLayers.item(index);
            if(!"lava_hazard".equals(candidate.getAttribute("name"))) { continue; }
            if(candidate.getParentNode()!=map) { throw new IllegalArgumentException("Nested lava layer"); }
            for(String offset:List.of("offsetx","offsety","x","y")) { if(number(candidate,offset,0)!=0) { throw new IllegalArgumentException("Lava offset unsupported"); } }
            var objects=candidate.getElementsByTagName("object");
            for(int i=0;i<objects.getLength();i++) {
                Element object=(Element)objects.item(i);rejectUnsupportedGeometry(object);
                float width=number(object,"width",0),height=number(object,"height",0);
                if(width<=0 || height<=0) { throw new IllegalArgumentException("Invalid lava zone"); }
                lavaZones.add(new MapCollisionDefinition.CollisionRectangle(Integer.parseInt(object.getAttribute("id")),number(object,"x",0),mapHeight-number(object,"y",0)-height,width,height));
            }
        }
        return new MapCollisionDefinition(sourceName, mapWidth, mapHeight, rectangles,lavaZones);
    }

    private static void rejectUnsupportedGeometry(Element object) {
        if (object.hasAttribute("gid") || object.hasAttribute("template") || number(object, "rotation", 0) != 0) {
            throw new IllegalArgumentException("Tile/template/rotation collision chưa hỗ trợ, ID=" + object.getAttribute("id"));
        }
        for (Node node = object.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && !"properties".equals(child.getTagName())) {
                throw new IllegalArgumentException("Collision geometry chưa hỗ trợ: " + child.getTagName()
                        + ", ID=" + object.getAttribute("id"));
            }
        }
    }

    private static float dimension(Element map, String name) {
        float value = number(map, name, 0);
        if (value <= 0 || value != Math.rint(value)) {
            throw new IllegalArgumentException("Map cần " + name + " là số nguyên dương");
        }
        return value;
    }

    private static float number(Element element, String name, float fallback) {
        try {
            float value = Float.parseFloat(attribute(element, name, Float.toString(fallback)));
            if (!Float.isFinite(value)) {
                throw new NumberFormatException("Giá trị không hữu hạn");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("TMX attribute không hợp lệ: " + name, failure);
        }
    }

    private static String attribute(Element element, String name, String fallback) {
        return element.hasAttribute(name) ? element.getAttribute(name) : fallback;
    }

    private static DocumentBuilder secureParser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entity bị từ chối"); });
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException failure) throws SAXException { throw failure; }

                @Override
                public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
            });
            return builder;
        } catch (ParserConfigurationException | IllegalArgumentException failure) {
            throw new IllegalStateException("Không cấu hình được XML parser an toàn", failure);
        }
    }
}

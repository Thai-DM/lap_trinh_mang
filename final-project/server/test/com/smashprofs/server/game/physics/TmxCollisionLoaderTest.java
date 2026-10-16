package com.smashprofs.server.game.physics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.maps.MapObjects;
import com.badlogic.gdx.maps.objects.RectangleMapObject;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.XmlReader;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class TmxCollisionLoaderTest {
    private final TmxCollisionLoader loader = new TmxCollisionLoader();

    @Test
    public void canLocateOriginalTmxAsset() {
        assertNotNull(TmxCollisionLoader.class.getResource("/" + TmxCollisionLoader.ORIGINAL_MAP_RESOURCE));
        assertEquals(TmxCollisionLoader.ORIGINAL_MAP_RESOURCE, loader.loadOriginalMap().sourceName());
    }

    @Test
    public void canParseCollisionLayerHeadlessly() {
        assertNull(Gdx.app);
        assertNull(Gdx.graphics);
        MapCollisionDefinition definition = loader.loadOriginalMap();
        assertFalse(definition.rectangles().isEmpty());
        assertEquals(1200.0f, definition.mapWidthPixels(), 0.0f);
        assertEquals(400.0f, definition.mapHeightPixels(), 0.0f);
        assertNull(Gdx.app);
        assertNull(Gdx.graphics);
        assertNull(Gdx.gl);
    }

    @Test
    public void rectangleCountMatchesTmx() throws Exception {
        assertEquals(18, originalRoot().getChildByName("objectgroup").getChildrenByName("object").size);
        assertEquals(originalRectangles().size(), loader.loadOriginalMap().rectangles().size());
    }

    @Test
    public void rectangleCoordinatesMatchOriginal() throws Exception {
        List<MapCollisionDefinition.CollisionRectangle> expected = originalRectangles();
        List<MapCollisionDefinition.CollisionRectangle> actual = loader.loadOriginalMap().rectangles();
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).objectId(), actual.get(index).objectId());
            assertEquals(expected.get(index).xPixels(), actual.get(index).xPixels(), 0.0f);
            assertEquals(expected.get(index).yPixels(), actual.get(index).yPixels(), 0.0f);
        }
        assertEquals(-0.25f, actual.get(0).yPixels(), 0.0f);
    }

    @Test
    public void rectangleDimensionsMatchOriginal() throws Exception {
        List<MapCollisionDefinition.CollisionRectangle> expected = originalRectangles();
        List<MapCollisionDefinition.CollisionRectangle> actual = loader.loadOriginalMap().rectangles();
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).widthPixels(), actual.get(index).widthPixels(), 0.0f);
            assertEquals(expected.get(index).heightPixels(), actual.get(index).heightPixels(), 0.0f);
        }
    }

    @Test
    public void originalZeroHeightRectangleIsPreservedWithoutAddingGeometry() {
        var rectangle = loader.loadOriginalMap().rectangles().stream()
                .filter(value -> value.objectId() == 35).findFirst().orElseThrow();
        assertEquals(0.0f, rectangle.heightPixels(), 0.0f);
        assertEquals(0.333333f, rectangle.widthPixels(), 0.0f);
        assertEquals(400.0f - 375.333f, rectangle.yPixels(), 0.0f);
    }

    @Test
    public void missingMapFailsClearly() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> loader.loadResource("1/missing-map.tmx"));
        assertTrue(failure.getMessage().contains("missing-map.tmx"));
    }

    @Test
    public void unsupportedGeometryFailsClearly() {
        for (String shape : List.of("<ellipse/>", "<polygon points='0,0 8,0 8,8'/>",
                "<polyline points='0,0 8,8'/>", "<point/>", "<text>text</text>")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> parse(map("<object id='1' x='4' y='8' width='16' height='24'>" + shape + "</object>")));
            assertTrue(failure.getMessage().contains("chưa hỗ trợ"));
        }
    }

    @Test
    public void unsupportedObjectTransformsAndTileObjectsAreRejected() {
        for (String attributes : List.of("rotation='15'", "gid='1'", "template='external.tx'")) {
            assertThrows(IllegalArgumentException.class,
                    () -> parse(map("<object id='1' width='16' height='24' " + attributes + "/>")));
        }
    }

    @Test
    public void unsupportedLayerOffsetsAreRejected() {
        for (String name : List.of("x", "y", "offsetx", "offsety")) {
            assertThrows(IllegalArgumentException.class,
                    () -> parse(map("<object id='1' width='16' height='24'/>")
                            .replace("name='obj'", "name='obj' " + name + "='1'")));
        }
    }

    @Test
    public void missingCollisionLayerIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse(map("").replace("name='obj'", "name='other'")));
    }

    @Test
    public void nestedOrDuplicateCollisionLayersAreRejected() {
        String xml = map("<object id='1' width='16' height='24'/>");
        assertThrows(IllegalArgumentException.class,
                () -> parse(xml.replace("<objectgroup", "<group><objectgroup")
                        .replace("</objectgroup>", "</objectgroup></group>")));
        assertThrows(IllegalArgumentException.class,
                () -> parse(xml.replace("</map>", "<objectgroup name='obj'/></map>")));
    }

    @Test
    public void geometryCollectionIsImmutableAndDetached() {
        MapCollisionDefinition definition = loader.loadOriginalMap();
        assertThrows(UnsupportedOperationException.class, () -> definition.rectangles().clear());
        List<MapCollisionDefinition.CollisionRectangle> original = new ArrayList<>(definition.rectangles());
        MapCollisionDefinition copy = new MapCollisionDefinition("copy", 1200, 400, original);
        original.clear();
        assertEquals(18, copy.rectangles().size());
    }

    @Test
    public void invalidGeometryDoesNotProducePartialDefinition() {
        for (String attribute : List.of("width='-1'", "height='-1'", "x='NaN'", "y='Infinity'")) {
            assertThrows(IllegalArgumentException.class, () -> parse(map("<object id='1' " + attribute + "/>")));
        }
        assertThrows(IllegalArgumentException.class, () -> parse(map("<object id='1'/><object id='1'/>")));
    }

    @Test
    public void unsupportedMapProjectionAndInfiniteMapAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse(map("").replace("orthogonal", "isometric")));
        assertThrows(IllegalArgumentException.class, () -> parse(map("").replace("infinite='0'", "infinite='1'")));
    }

    @Test
    public void doctypeAndExternalEntitiesAreRejected() {
        String doctype = "<!DOCTYPE map [<!ENTITY external SYSTEM 'file:///no-such-collision-test-resource'>]>";
        assertThrows(IllegalArgumentException.class, () -> parse(doctype + map("&external;")));
        assertThrows(IllegalArgumentException.class,
                () -> parse("<!DOCTYPE map SYSTEM 'urn:forbidden-external-dtd'>" + map("")));
    }

    @Test
    public void malformedXmlFailsClearly() {
        assertThrows(IllegalArgumentException.class, () -> parse("<map>"));
    }

    @Test(timeout = 15000)
    public void mapCanBeLocatedFromDifferentWorkingDirectory() throws Exception {
        Path directory = Files.createTempDirectory("tmx-classpath-test-");
        Process child = null;
        try {
            Path resourceRoot = Path.of(TmxCollisionLoader.class
                    .getResource("/" + TmxCollisionLoader.ORIGINAL_MAP_RESOURCE).toURI()).getParent().getParent();
            String classpath = String.join(File.pathSeparator,
                    Path.of(TmxCollisionLoaderTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                    Path.of(TmxCollisionLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                    resourceRoot.toString());
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            child = new ProcessBuilder(java.toString(), "-cp", classpath, AssetPathProbe.class.getName())
                    .directory(directory.toFile()).redirectErrorStream(true).start();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 0, child.exitValue());
            assertTrue(output, output.contains("rectangles=18"));
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(2, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(directory);
        }
    }

    private MapCollisionDefinition parse(String xml) {
        return loader.load(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "test.tmx");
    }

    private static String map(String objects) {
        return "<map orientation='orthogonal' width='150' height='50' tilewidth='8' tileheight='8' infinite='0'>"
                + "<objectgroup name='obj'>" + objects + "</objectgroup></map>";
    }

    static List<MapCollisionDefinition.CollisionRectangle> originalRectangles() throws Exception {
        XmlReader.Element root = originalRoot();
        float mapHeight = root.getIntAttribute("height") * root.getIntAttribute("tileheight");
        BaselineObjectLoader baseline = new BaselineObjectLoader();
        List<MapCollisionDefinition.CollisionRectangle> result = new ArrayList<>();
        for (XmlReader.Element object : root.getChildByName("objectgroup").getChildrenByName("object")) {
            Rectangle rectangle = baseline.rectangle(object, mapHeight);
            result.add(new MapCollisionDefinition.CollisionRectangle(object.getIntAttribute("id"),
                    rectangle.x, rectangle.y, rectangle.width, rectangle.height));
        }
        return List.copyOf(result);
    }

    private static XmlReader.Element originalRoot() throws Exception {
        try (InputStream input = TmxCollisionLoader.class.getResourceAsStream("/" + TmxCollisionLoader.ORIGINAL_MAP_RESOURCE)) {
            assertNotNull(input);
            return new XmlReader().parse(input);
        }
    }

    /** Chỉ gọi nhánh load object của LibGDX 1.10.0 làm oracle, không gọi load texture/tileset/map renderer. */
    private static final class BaselineObjectLoader extends TmxMapLoader {
        BaselineObjectLoader() {
            super(FileHandle::new);
        }

        Rectangle rectangle(XmlReader.Element object, float mapHeight) {
            try (EmptyMap ignored = new EmptyMap()) {
                MapObjects objects = new MapObjects();
                loadObject(ignored.map, objects, object, mapHeight);
                assertEquals(1, objects.getCount());
                return new Rectangle(((RectangleMapObject) objects.get(0)).getRectangle());
            }
        }
    }

    private static final class EmptyMap implements AutoCloseable {
        private final TiledMap map = new TiledMap();

        @Override
        public void close() {
            map.dispose();
        }
    }

    /** JVM con kiểm tra classpath thật trong working directory tạm, không cần JUnit/Gdx trong JVM con. */
    public static final class AssetPathProbe {
        public static void main(String[] args) {
            MapCollisionDefinition definition = new TmxCollisionLoader().loadOriginalMap();
            if (definition.rectangles().size() != 18) {
                throw new IllegalStateException("Sai collision object count");
            }
            System.out.println("rectangles=" + definition.rectangles().size());
        }
    }
}

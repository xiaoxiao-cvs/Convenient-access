package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;

/**
 * GET /api/v1/item-icon?id=&lt;ns:path&gt; 公开端点。
 *
 * 从已加载 mod jar 抽取物品贴图 PNG 字节并以 image/png 返回。资源经 Forge 的
 * {@link IModFile#findResource(String...)} 在 SecureJar 虚拟文件系统内定位, 对
 * jar-in-jar (整合包嵌套库) 透明, 避免 Path.toFile() 在非默认 FS 上抛
 * UnsupportedOperationException。
 *
 * 解析顺序 (best-effort):
 *  1. assets/&lt;ns&gt;/models/item/&lt;path&gt;.json 的 textures.layer0 -&gt; 对应贴图 PNG
 *  2. 回退 assets/&lt;ns&gt;/textures/item/&lt;path&gt;.png
 *  3. 回退 assets/&lt;ns&gt;/textures/block/&lt;path&gt;.png
 *  4. 都没有 -&gt; 404 (前端显占位)。方块/复杂模型 (无 layer0 的 parent 链) 不强行解析。
 *
 * 命中结果按 id 缓存 PNG 字节; ns-&gt;jarPath 解析结果亦缓存, 避免每个 &lt;img&gt; 请求重走 ModList。
 * 未命中以 {@link #MISS} 哨兵缓存, 避免重复扫描不存在的资源。
 */
public final class ItemIconHandler {

    private static final Logger logger = LoggerFactory.getLogger(ItemIconHandler.class);

    /** 字符白名单: 小写字母/数字/下划线/点/冒号/斜杠/连字符。冒号分隔 ns:path, 斜杠见于 path 段。 */
    private static final Pattern ID_ALLOWED = Pattern.compile("[a-z0-9_.:/-]+");

    /** 未命中哨兵, 与命中字节区分, 用于负缓存。 */
    private static final byte[] MISS = new byte[0];

    /** id (规范化后的 ns:path) -> PNG 字节, MISS 表示已确认无此图标。 */
    private final ConcurrentHashMap<String, byte[]> iconCache = new ConcurrentHashMap<>();

    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String raw = request.getParameter("id");
        if (raw == null || raw.isEmpty()) {
            sendError(response, 400, "缺少 id 参数");
            return;
        }

        // 容忍 "ns/path" 写法 (浏览器/代理对冒号处理不一致时前端可改用斜杠): 仅替换首个斜杠为冒号,
        // 后续斜杠保留给 path 段 (如 minecraft/some/nested 不适用物品, 但首段分隔语义明确)。
        String id = raw;
        if (id.indexOf(':') < 0) {
            id = id.replaceFirst("/", ":");
        }
        id = id.toLowerCase(java.util.Locale.ROOT);

        // 严格校验: 拒绝路径穿越与非法字符。先查 ".." 再查白名单。
        if (id.contains("..") || !ID_ALLOWED.matcher(id).matches()) {
            sendError(response, 400, "非法 id: 仅允许 [a-z0-9_.:/-], 禁止 '..'");
            return;
        }

        int colon = id.indexOf(':');
        String ns;
        String path;
        if (colon < 0) {
            ns = "minecraft";
            path = id;
        } else {
            ns = id.substring(0, colon);
            path = id.substring(colon + 1);
        }
        if (ns.isEmpty() || path.isEmpty()) {
            sendError(response, 400, "非法 id: namespace 与 path 均不能为空");
            return;
        }

        // 规范化缓存 key (大小写已归一, 含 ns:path 全貌)。
        String cacheKey = ns + ":" + path;
        byte[] png = iconCache.get(cacheKey);
        if (png == null) {
            png = resolveIcon(ns, path);
            iconCache.put(cacheKey, png == null ? MISS : png);
        }
        if (png == null || png == MISS) {
            sendError(response, 404, "未找到物品图标: " + cacheKey);
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("image/png");
        response.setContentLength(png.length);
        response.setHeader("Cache-Control", "public, max-age=86400");
        try (ServletOutputStream os = response.getOutputStream()) {
            os.write(png);
            os.flush();
        }
    }

    /**
     * best-effort 解析图标 PNG 字节, 失败返回 null。
     * 不吞底层 IO 异常的语义: 资源缺失 (findResource 返回 null) 是正常的"未找到", 返回 null;
     * 读取已存在资源时的真实 IO 故障让其冒泡到 handle 的 try/catch 由 ApiRouter 统一处理。
     */
    private byte[] resolveIcon(String ns, String path) throws IOException {
        // 1. 物品模型 json 的 layer0
        byte[] modelJson = readResource(ns, "assets", ns, "models", "item", path + ".json");
        if (modelJson != null) {
            String layer0 = extractLayer0(modelJson);
            if (layer0 != null) {
                byte[] tex = readTexture(layer0);
                if (tex != null) {
                    return tex;
                }
            }
        }

        // 2. 回退: 直接物品贴图
        byte[] itemTex = readResource(ns, "assets", ns, "textures", "item", path + ".png");
        if (itemTex != null) {
            return itemTex;
        }

        // 3. 回退: 方块贴图 (方块物品常见: 贴图名与物品 id 同名)
        byte[] blockTex = readResource(ns, "assets", ns, "textures", "block", path + ".png");
        if (blockTex != null) {
            return blockTex;
        }

        return null;
    }

    /** 解析 textures.layer0 文本引用 (形如 "tns:item/name" 或 "item/name"), 无则返回 null。 */
    private String extractLayer0(byte[] modelJson) {
        try {
            JsonObject root = JsonParser.parseString(new String(modelJson, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("textures") || !root.get("textures").isJsonObject()) {
                return null;
            }
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures.has("layer0") && textures.get("layer0").isJsonPrimitive()) {
                return textures.get("layer0").getAsString();
            }
            return null;
        } catch (RuntimeException e) {
            // 模型 json 损坏/非对象: best-effort 放弃, 走回退路径而非 500
            logger.debug("解析物品模型 json 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把贴图引用 "tns:texPath" 解析为 PNG 字节。规则: 无冒号则 ns=minecraft;
     * "&lt;tns&gt;:&lt;texPath&gt;" -&gt; assets/&lt;tns&gt;/textures/&lt;texPath&gt;.png。
     * texPath 可含子目录 (如 "item/apple"), 拆为多段传给 findResource。
     */
    private byte[] readTexture(String ref) throws IOException {
        if (ref.contains("..") || !ID_ALLOWED.matcher(ref).matches()) {
            return null;
        }
        String tns;
        String texPath;
        int colon = ref.indexOf(':');
        if (colon < 0) {
            tns = "minecraft";
            texPath = ref;
        } else {
            tns = ref.substring(0, colon);
            texPath = ref.substring(colon + 1);
        }
        if (tns.isEmpty() || texPath.isEmpty()) {
            return null;
        }

        String[] texSegs = texPath.split("/");
        String[] segs = new String[texSegs.length + 3];
        segs[0] = "assets";
        segs[1] = tns;
        segs[2] = "textures";
        System.arraycopy(texSegs, 0, segs, 3, texSegs.length);
        segs[segs.length - 1] = segs[segs.length - 1] + ".png";
        return readResource(tns, segs);
    }

    /**
     * 经 ModList 定位 ns 对应 mod jar, 用 IModFile.findResource 在其 SecureJar FS 内读资源。
     * 资源不存在返回 null。jarOwnerNs 用于定位拥有该资源的 mod (通常 = 资源 ns)。
     */
    private byte[] readResource(String jarOwnerNs, String... pathName) throws IOException {
        IModFile modFile = resolveModFile(jarOwnerNs);
        if (modFile == null) {
            return null;
        }
        Path res = modFile.findResource(pathName);
        if (res == null || !Files.exists(res)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(res)) {
            return in.readAllBytes();
        }
    }

    /** ns -&gt; 拥有 assets/&lt;ns&gt;/ 的 IModFile。vanilla 资源的 ns="minecraft", modId 同名。 */
    private IModFile resolveModFile(String ns) {
        IModFileInfo info = ModList.get().getModFileById(ns);
        if (info == null) {
            return null;
        }
        return info.getFile();
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                String.format("{\"success\": false, \"error\": {\"code\": %d, \"message\": \"%s\"}, \"timestamp\": %d}",
                        status, message.replace("\"", "'"), System.currentTimeMillis()));
        response.getWriter().flush();
    }
}

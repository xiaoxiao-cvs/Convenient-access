package com.shinoyuki.accesshub.api;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shinoyuki.accesshub.auth.AdminUser;
import com.shinoyuki.accesshub.auth.PersonalCodeManager;
import com.shinoyuki.accesshub.auth.QqBindingDao;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 个人识别码与 QQ 绑定的 HTTP 入口。
 *
 * 两组端点服务两类调用方, 鉴权面不同 (由 ApiRouter 统一把关):
 * - /api/v1/admin/personal-code : 面板管理员本人, 必须携带 JWT —— 签发的是"我的"码, 必须知道我是谁
 * - /api/v1/bot/*               : QQ Bot 后台, 携带 X-API-Key —— 代表机器人身份, 具体是谁由识别码认领
 */
public final class BindingApiController {

    private static final Logger logger = LoggerFactory.getLogger(BindingApiController.class);

    /** QQ 号: 5-15 位数字且不以 0 开头。挡掉明显的乱填, 真实性由绑定流程本身保证。 */
    private static final Pattern QQ_PATTERN = Pattern.compile("^[1-9][0-9]{4,14}$");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final PersonalCodeManager personalCodeManager;
    private final QqBindingDao qqBindingDao;

    public BindingApiController(PersonalCodeManager personalCodeManager, QqBindingDao qqBindingDao) {
        this.personalCodeManager = personalCodeManager;
        this.qqBindingDao = qqBindingDao;
    }

    /**
     * GET /api/v1/admin/personal-code — 查询当前管理员的识别码掩码与已绑 QQ。
     */
    public void handleGetPersonalCode(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AdminUser admin = requireAdmin(request, response);
        if (admin == null) {
            return;
        }
        try {
            long adminId = admin.getId();
            PersonalCodeManager.PersonalCodeStatus status = personalCodeManager.getStatus(adminId).get();
            List<String> boundQq = qqBindingDao.listQqByAdmin(adminId).get();

            JsonObject data = new JsonObject();
            data.addProperty("issued", status != null);
            data.addProperty("codeLength", PersonalCodeManager.CODE_LENGTH);
            if (status != null) {
                data.addProperty("prefix", status.prefix());
                data.addProperty("suffix", status.suffix());
                data.addProperty("issuedAt", status.issuedAt() == null ? null : status.issuedAt().format(ISO));
            }
            JsonArray qqArray = new JsonArray();
            boundQq.forEach(qqArray::add);
            data.add("boundQq", qqArray);

            ApiSupport.sendJson(response, 200, ApiResponse.success(data, "获取成功"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ApiSupport.sendJson(response, 500, ApiResponse.error("查询被中断"));
        } catch (Exception e) {
            logger.error("查询个人识别码状态失败", e);
            ApiSupport.sendJson(response, 500, ApiResponse.error("查询个人识别码失败"));
        }
    }

    /**
     * POST /api/v1/admin/personal-code — 签发或重置当前管理员的识别码, 明文仅此一次返回。
     */
    public void handleIssuePersonalCode(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AdminUser admin = requireAdmin(request, response);
        if (admin == null) {
            return;
        }
        try {
            String code = personalCodeManager.issue(admin.getId()).get();

            JsonObject data = new JsonObject();
            data.addProperty("code", code);
            data.addProperty("codeLength", PersonalCodeManager.CODE_LENGTH);
            data.addProperty("message", "识别码只显示这一次, 请立即复制保存");

            ApiSupport.sendJson(response, 200, ApiResponse.success(data, "识别码已签发"));
            logger.info("管理员 {} (id={}) 签发个人识别码, 旧码已失效", admin.getUsername(), admin.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ApiSupport.sendJson(response, 500, ApiResponse.error("签发被中断"));
        } catch (Exception e) {
            logger.error("签发个人识别码失败", e);
            ApiSupport.sendJson(response, 500, ApiResponse.error("签发个人识别码失败"));
        }
    }

    /**
     * POST /api/v1/bot/bind — Bot 用管理员私聊发来的识别码认领 QQ 号。
     */
    public void handleBotBind(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            JsonObject json = parseBody(request);
            if (json == null) {
                ApiSupport.sendJson(response, 400, ApiResponse.badRequest("请求体必须是 JSON 对象"));
                return;
            }
            String code = optString(json, "code");
            String qq = optString(json, "qq");

            if (code == null || qq == null) {
                ApiSupport.sendJson(response, 400, ApiResponse.badRequest("缺少必要参数: code, qq"));
                return;
            }
            if (!QQ_PATTERN.matcher(qq).matches()) {
                ApiSupport.sendJson(response, 400, ApiResponse.badRequest("QQ 号格式无效"));
                return;
            }
            // 格式不合法直接按"码无效"回应, 不区分"格式错"与"码不存在", 免得成为探测口径
            if (!PersonalCodeManager.isWellFormed(code)) {
                ApiSupport.sendJson(response, 403, ApiResponse.forbidden("识别码无效"));
                return;
            }

            Long adminId = personalCodeManager.resolveAdminId(code).get();
            if (adminId == null) {
                logger.warn("QQ {} 使用无效识别码尝试绑定, 来源 {}", qq, ApiSupport.getClientIp(request));
                ApiSupport.sendJson(response, 403, ApiResponse.forbidden("识别码无效"));
                return;
            }

            QqBindingDao.BindOutcome outcome = qqBindingDao.bind(adminId, qq).get();
            if (outcome.status() == QqBindingDao.BindStatus.CONFLICT) {
                logger.warn("QQ {} 绑定失败: 已被管理员 id={} 占用", qq,
                        outcome.binding() == null ? "?" : outcome.binding().adminId());
                ApiSupport.sendJson(response, 409, ApiResponse.error(409, "该 QQ 已绑定到其他管理员, 请先解绑"));
                return;
            }
            if (outcome.binding() == null) {
                // 绑定行刚写入却查不回来, 说明管理员账号已不存在 (JOIN 落空), 属于数据不一致
                logger.error("QQ {} 绑定后回查为空, admin_id={} 可能已被删除", qq, adminId);
                ApiSupport.sendJson(response, 500, ApiResponse.error("绑定状态异常, 请联系管理员"));
                return;
            }
            if (!outcome.binding().adminActive()) {
                ApiSupport.sendJson(response, 403, ApiResponse.forbidden("该管理员账号已停用"));
                return;
            }

            JsonObject data = bindingJson(outcome.binding());
            data.addProperty("alreadyBound", outcome.status() == QqBindingDao.BindStatus.ALREADY_BOUND);
            ApiSupport.sendJson(response, 200, ApiResponse.success(data,
                    outcome.status() == QqBindingDao.BindStatus.ALREADY_BOUND ? "该 QQ 早已绑定" : "绑定成功"));
            logger.info("QQ {} 绑定到管理员 {} (id={}), status={}",
                    qq, outcome.binding().adminUsername(), adminId, outcome.status());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ApiSupport.sendJson(response, 500, ApiResponse.error("绑定被中断"));
        } catch (Exception e) {
            logger.error("处理 QQ 绑定请求失败", e);
            ApiSupport.sendJson(response, 500, ApiResponse.error("绑定失败"));
        }
    }

    /**
     * GET /api/v1/bot/binding?qq= — Bot 在执行每条运维命令前查发令人身份。
     */
    public void handleBotGetBinding(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String qq = request.getParameter("qq");
        if (qq == null || !QQ_PATTERN.matcher(qq).matches()) {
            ApiSupport.sendJson(response, 400, ApiResponse.badRequest("缺少或非法的 qq 参数"));
            return;
        }
        try {
            QqBindingDao.QqBinding binding = qqBindingDao.findByQq(qq).get();
            JsonObject data = new JsonObject();
            data.addProperty("qq", qq);
            // 账号停用等同于未绑定: 让 Bot 只看 bound 一个字段即可决定放不放行
            boolean usable = binding != null && binding.adminActive();
            data.addProperty("bound", usable);
            if (binding != null) {
                data.addProperty("adminActive", binding.adminActive());
                if (usable) {
                    data.addProperty("adminId", binding.adminId());
                    data.addProperty("adminUsername", binding.adminUsername());
                    data.addProperty("adminDisplayName", binding.displayLabel());
                    data.addProperty("boundAt", binding.boundAt() == null ? null : binding.boundAt().format(ISO));
                }
            }
            ApiSupport.sendJson(response, 200, ApiResponse.success(data, "查询成功"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ApiSupport.sendJson(response, 500, ApiResponse.error("查询被中断"));
        } catch (Exception e) {
            logger.error("查询 QQ 绑定失败: {}", qq, e);
            ApiSupport.sendJson(response, 500, ApiResponse.error("查询绑定失败"));
        }
    }

    /**
     * DELETE /api/v1/bot/binding?qq= — 解除绑定。
     */
    public void handleBotUnbind(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String qq = request.getParameter("qq");
        if (qq == null || !QQ_PATTERN.matcher(qq).matches()) {
            ApiSupport.sendJson(response, 400, ApiResponse.badRequest("缺少或非法的 qq 参数"));
            return;
        }
        try {
            boolean removed = qqBindingDao.unbind(qq).get();
            JsonObject data = new JsonObject();
            data.addProperty("qq", qq);
            data.addProperty("unbound", removed);
            if (removed) {
                logger.info("QQ {} 已解除绑定", qq);
                ApiSupport.sendJson(response, 200, ApiResponse.success(data, "已解除绑定"));
            } else {
                ApiSupport.sendJson(response, 404, ApiResponse.notFound("该 QQ 未绑定任何管理员"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ApiSupport.sendJson(response, 500, ApiResponse.error("解绑被中断"));
        } catch (Exception e) {
            logger.error("解除 QQ 绑定失败: {}", qq, e);
            ApiSupport.sendJson(response, 500, ApiResponse.error("解绑失败"));
        }
    }

    /**
     * 取 ApiRouter 在 JWT 校验通过时挂上的当前管理员。
     * 拿不到就是 401: 用 X-API-Key 访问, 或 api.auth.enabled=false 全局放行时都无法确定"我是谁",
     * 而个人识别码必须归属到具体账号, 不能凭机器身份签发。
     */
    private AdminUser requireAdmin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object attr = request.getAttribute("currentUser");
        if (attr instanceof AdminUser admin && admin.getId() != null) {
            return admin;
        }
        ApiSupport.sendJson(response, 401, ApiResponse.unauthorized("该操作需要管理员登录 (Authorization: Bearer <JWT>)"));
        return null;
    }

    private JsonObject bindingJson(QqBindingDao.QqBinding binding) {
        JsonObject data = new JsonObject();
        data.addProperty("qq", binding.qq());
        data.addProperty("adminId", binding.adminId());
        data.addProperty("adminUsername", binding.adminUsername());
        data.addProperty("adminDisplayName", binding.displayLabel());
        data.addProperty("boundAt", binding.boundAt() == null ? null : binding.boundAt().format(ISO));
        return data;
    }

    private JsonObject parseBody(HttpServletRequest request) throws IOException {
        String body = ApiSupport.readRequestBody(request);
        if (body.isBlank()) {
            return null;
        }
        var parsed = JsonParser.parseString(body);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private String optString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        String value = json.get(key).getAsString().trim();
        return value.isEmpty() ? null : value;
    }
}

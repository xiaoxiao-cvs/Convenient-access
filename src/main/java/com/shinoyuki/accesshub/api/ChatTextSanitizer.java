package com.shinoyuki.accesshub.api;

/**
 * 外部渠道文本进入游戏公屏前的清洗规则。
 *
 * 单独成类而非留在 {@link ChatBridgeHandlerImpl} 里, 是为了让规则能在不加载任何 Minecraft 类的
 * 前提下被单元测试直接覆盖 —— 测试期没有 MC 运行环境。
 */
public final class ChatTextSanitizer {

    private ChatTextSanitizer() {
    }

    /**
     * 剥掉分节符与控制字符 (含换行、制表) 并按 maxLength 截断, 再去首尾空白。
     *
     * 分节符必须剥: 留着它, 任何人都能用 §c 之类把自己的发言染成系统提示的颜色, 或用 §k 乱码刷屏。
     * 控制字符必须剥: 换行会让一句话在公屏撑成多行, 足以伪造出一段假公告。
     *
     * @return 清洗后的文本; 原文为 null 或洗完只剩空白时返回 null, 由调用方按"缺失"处理
     */
    public static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length() && sb.length() < maxLength; i++) {
            char c = raw.charAt(i);
            if (c == '§' || Character.isISOControl(c)) {
                continue;
            }
            sb.append(c);
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}

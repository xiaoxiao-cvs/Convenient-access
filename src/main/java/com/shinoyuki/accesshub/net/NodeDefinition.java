package com.shinoyuki.accesshub.net;

/**
 * 一条接入线路的定义, 由配置文件 network.nodes 提供。
 *
 * 线路归属靠"玩家从哪个本地端口进来"判定, 而不是靠玩家输入的域名: 域名可以被玩家换成 IP 直连,
 * 端口则是物理事实, 无法伪造也不会漏判。
 *
 * @param id          线路标识, 统计接口与前端按此聚合, 例如 gz / sz / home
 * @param displayName 展示名, 例如 "阿里云广州"
 * @param listenPort  转发器在本机监听的入口端口, 对应 frpc 配置里该线路 proxy 的 localPort
 * @param endpoint    给玩家填进游戏客户端的地址, 例如 gz.mcwok.cn:25565
 * @param probeUrl    该线路的延迟探针地址, 例如 wss://gz.mcwok.cn/probe; 留空表示这条线不做探测
 */
public record NodeDefinition(
        String id,
        String displayName,
        int listenPort,
        String endpoint,
        String probeUrl) {

    public NodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("线路 id 不能为空");
        }
        if (listenPort < 1 || listenPort > 65535) {
            throw new IllegalArgumentException("线路 " + id + " 的 listen-port 非法: " + listenPort);
        }
    }
}

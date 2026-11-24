package com.scenic.digitalhuman.config;

import com.scenic.digitalhuman.chat.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置（第十一周任务A，PPT P06"最小代码"口径）。
 *
 * 端点契约：/ws/qa（接口文档 v1.3 是唯一契约源，改契约先在群里说）。
 * 跨域放行 setAllowedOriginPatterns("*")：WS 鉴权不在握手层做 CORS 卡控，
 * 而是握手后由 handler 校验 token 参数（见 ChatWebSocketHandler，PPT P16）。
 *
 * 不配 ServletServerContainerFactoryBean（review 修正）：MockMvc 测试环境的
 * MockServletContext 没有 jakarta.websocket ServerContainer，该 Bean 会导致
 * @SpringBootTest 全部起不来（11-22 集成测试实测）。僵尸连接防护由两层覆盖：
 * ① 前端 30s 心跳保活（PPT P07，连接上始终有流量，容器 idle 不会触发）；
 * ② Redis 在线表 TTL 90s（PPT P13，进程被杀没注销也会自动过期）——
 * 这才是防僵尸的正解，容器级空闲超时只是辅助，舍弃不影响正确性。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/qa")
                .setAllowedOriginPatterns("*");
    }
}

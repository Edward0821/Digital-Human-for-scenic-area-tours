package com.scenic.digitalhuman.config;

import com.scenic.digitalhuman.chat.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置（第十一周任务A，PPT P06"最小代码"口径）。
 *
 * 端点契约：/ws/qa（接口文档 v1.3 是唯一契约源，改契约先在群里说）。
 * 跨域放行 setAllowedOriginPatterns("*")：WS 鉴权不在握手层做 CORS 卡控，
 * 而是握手后由 handler 校验 token 参数（见 ChatWebSocketHandler，PPT P16）。
 *
 * 空闲超时 90 秒 = 心跳 30 秒 ×3（容忍一次心跳丢失）。口径 PPT P07：
 * 超时太长连接被防火墙/NAT 回收，太短被当攻击扫描；30 秒心跳是业界常用值。
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

    /** 容器级参数：90 秒空闲超时；单帧 64KB 上限（防超大帧打内存） */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(90_000L);
        container.setMaxTextMessageBufferSize(64 * 1024);
        return container;
    }
}

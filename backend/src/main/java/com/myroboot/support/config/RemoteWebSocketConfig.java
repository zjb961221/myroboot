package com.myroboot.support.config;

import com.myroboot.support.remote.RemoteAgentWebSocketHandler;
import com.myroboot.support.remote.RemoteBrowserTerminalWebSocketHandler;
import com.myroboot.support.remote.RemoteDesktopService;
import com.myroboot.support.remote.RemoteTerminalBroker;
import com.myroboot.support.remote.RemoteTerminalService;
import com.myroboot.support.service.RemoteAgentService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RemoteWebSocketConfig implements WebSocketConfigurer {
    private final RemoteAgentService agentService;
    private final RemoteTerminalBroker broker;
    private final RemoteTerminalService terminalService;
    private final RemoteDesktopService desktopService;

    public RemoteWebSocketConfig(RemoteAgentService agentService, RemoteTerminalBroker broker, RemoteTerminalService terminalService, RemoteDesktopService desktopService) {
        this.agentService = agentService;
        this.broker = broker;
        this.terminalService = terminalService;
        this.desktopService = desktopService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RemoteAgentWebSocketHandler(agentService, broker, terminalService, desktopService), "/api/remote/ws/agent")
                .setAllowedOrigins("*");
        registry.addHandler(new RemoteBrowserTerminalWebSocketHandler(broker, terminalService), "/api/remote/ws/terminal")
                .setAllowedOrigins("*");
    }
}

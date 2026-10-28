package com.zenithbank.gateway.switch_;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@Component
public class MaintenanceSwitchFilter extends AbstractGatewayFilterFactory<MaintenanceSwitchFilter.Config> {

    private static final Logger log = Logger.getLogger(MaintenanceSwitchFilter.class.getName());
    private final ServiceSwitchRegistry registry;

    public MaintenanceSwitchFilter(ServiceSwitchRegistry registry) {
        super(Config.class);
        this.registry = registry;
    }
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            if (!registry.isServiceEnabled(config.getServiceName())) {
                log.info("MAINTENANCE: blocking request to " + config.getServiceName());
                return maintenanceResponse(exchange, config.getServiceName());
            }
            return chain.filter(exchange);
        };
    }

    private Mono<Void> maintenanceResponse(ServerWebExchange exchange, String svc) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 503);
        body.put("error", "Service Unavailable");
        body.put("service", svc);
        body.put("message", registry.getMaintenanceMessage(svc));
        String eta = registry.getMaintenanceEta(svc);
        if (eta != null) body.put("eta", eta);
        body.put("timestamp", LocalDateTime.now().toString());
        try {
            byte[] b = mapper.writeValueAsBytes(body);
            DataBuffer buf = exchange.getResponse().bufferFactory().wrap(b);
            return exchange.getResponse().writeWith(Mono.just(buf));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    public static class Config {
        private String serviceName;
        public String getServiceName() { return serviceName; }
        public void setServiceName(String n) { this.serviceName = n; }
    }
}

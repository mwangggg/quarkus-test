package org.acme;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;

@ApplicationScoped
public class AppLifecycle {

    volatile PluginInfo plugin;
    @Inject @RestClient CryostatService cryostat;
    Future<?> submission;

    @ConfigProperty(name = "quarkus.application.name") String appName;

    void onStart(@Observes StartupEvent ev) {
        this.submission = Executors.newSingleThreadExecutor().submit(() -> {
            while (plugin == null) {
                try {
                    RegistrationInfo registration = new RegistrationInfo();
                    registration.realm = "quarkus-test";
                    registration.callback = "http://localhost/unimplemented-callback";
                    JsonObject response = cryostat.register(registration);
                    this.plugin = response.getJsonObject("data").getJsonObject("result").mapTo(PluginInfo.class);

                    Node selfNode = new Node();
                    selfNode.nodeType = "JVM";
                    selfNode.name = "quarkus-test-" + this.plugin.id;
                    selfNode.target = new Node.Target();
                    selfNode.target.alias = appName;

                    String hostname = System.getProperty("java.rmi.server.hostname", "localhost");
                    int jmxport = Integer.valueOf(System.getProperty("com.sun.management.jmxremote.port", "9097"));

                    selfNode.target.connectUrl = URI.create(String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", hostname, jmxport));
                    System.out.println("registering self as " + selfNode.target.connectUrl);
                    cryostat.update(this.plugin.id, this.plugin.token, Set.of(selfNode));
                } catch (Exception e) {
                    e.printStackTrace();
                    deregister();
                }

                if (this.plugin != null) {
                    break;
                }
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                    break;
                }
            }
        });
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (this.submission != null) {
            this.submission.cancel(true);
        }
        deregister();
    }

    private void deregister() {
        if (plugin != null) {
            try {
                cryostat.deregister(this.plugin.id, this.plugin.token);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}

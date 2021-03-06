package com.otsample.api.localApp;

import brave.Tracing;
import brave.opentracing.BraveTracer;
import com.lightstep.tracer.jre.JRETracer;
import com.lightstep.tracer.shared.Options;
import com.otsample.api.remoteApp.KitchenContextHandler;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import zipkin.reporter.AsyncReporter;
import zipkin.reporter.Reporter;
import zipkin.reporter.Sender;
import zipkin.reporter.okhttp3.OkHttpSender;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class App {
    static final String CONFIG = "tracer_config.properties";

    public static void main(String[] args) throws Exception {

        Properties config = loadConfig();

        if (!configureGlobalTracer(config, "MicroDonuts")) {
            throw new Exception("Could not configure the global tracer");
        }


        ResourceHandler filesHandler = new ResourceHandler();
        filesHandler.setWelcomeFiles(new String[]{"./index.html"});
        filesHandler.setResourceBase(config.getProperty("public_directory"));

        ContextHandler fileCtxHandler = new ContextHandler();
        fileCtxHandler.setHandler(filesHandler);

        ContextHandlerCollection handlers = new ContextHandlerCollection();
        handlers.setHandlers(new Handler[]{
                fileCtxHandler,
                new ApiContextHandler(config),
                new KitchenContextHandler(config),
        });

        final Server server = new Server(10001);
        server.setHandler(handlers);
        server.start();
        server.dumpStdErr();
        server.join();

    }

    static Properties loadConfig() throws Exception {
        Properties config = new Properties();
        config.load(ClassLoader.getSystemResourceAsStream(CONFIG));
        return config;
    }

    static boolean configureGlobalTracer(Properties config, String componentName) throws MalformedURLException {
        String tracerName = config.getProperty("tracer");
        if ("jaeger".equals(tracerName)) {
            GlobalTracer.register(
                    new com.uber.jaeger.Configuration(
                            componentName,
                            new com.uber.jaeger.Configuration.SamplerConfiguration("const", 1),
                            new com.uber.jaeger.Configuration.ReporterConfiguration(
                                    true,  // logSpans
                                    config.getProperty("jaeger.reporter_host"),
                                    Integer.decode(config.getProperty("jaeger.reporter_port")),
                                    1000,   // flush interval in milliseconds
                                    10000)  // max buffered Spans
                    ).getTracer());
        } else if ("zipkin".equals(tracerName)) {
            Sender sender = OkHttpSender.create(
                    "http://" +
                            config.getProperty("zipkin.reporter_host") + ":" +
                            config.getProperty("zipkin.reporter_port") + "/api/v1/spans");
            Reporter reporter = AsyncReporter.builder(sender).build();
            GlobalTracer.register(BraveTracer.create(Tracing.newBuilder()
                    .localServiceName(componentName)
                    .reporter(reporter)
                    .build()));
        } else if ("lightstep".equals(tracerName)) {
            Options opts = new Options.OptionsBuilder()
                    .withAccessToken(config.getProperty("lightstep.access_token"))
                    .withCollectorHost(config.getProperty("lightstep.collector_host"))
                    .withCollectorPort(Integer.decode(config.getProperty("lightstep.collector_port")))
                    .withComponentName(componentName)
                    .build();
            Tracer tracer = new JRETracer(opts);
            GlobalTracer.register(tracer);
        } else {
            return false;
        }

        return true;
    }
}

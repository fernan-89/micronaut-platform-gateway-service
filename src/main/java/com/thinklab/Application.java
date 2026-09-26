package com.thinklab;

import com.thinklab.kit.telemetry.ReactorMdcBridge;
import io.micronaut.runtime.Micronaut;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;

import java.net.InetAddress;
import java.security.Security;
import java.util.TimeZone;

/**
 * Main Entry Point: Bootstrap class for the Thinklab Party Reference Data Directory Service.
 *
 * <p><b>Architectural Role:</b>
 * This class orchestrates the application bootstrap sequence using the Micronaut framework, ensuring
 * high scalability, low memory footprint, and non-blocking asynchronous execution. It also establishes
 * critical JVM-level security, time-drift, and reactive context-propagation invariants before the
 * inversion of control (IoC) container boots.
 *
 * <p><b>Contractual Obligations:</b>
 * <ul>
 * <li><b>Universal UTC Invariant:</b> Forcefully normalizes the JVM default timezone to UTC to prevent
 *     distributed time drift across entity lifecycles.</li>
 * <li><b>Execution Context Forensics:</b> Injects a pre-flight MDC (Mapped Diagnostic Context) state
 *     to ensure startup logs display host IPs instead of [NONE].</li>
 * <li><b>Reactive Context Propagation (MDC Bridge):</b> Enables automatic Project Reactor hooks to
 *     inherit MDC states across non-blocking asynchronous thread boundaries.</li>
 * <li><b>Unhandled Exception Guard:</b> Configures a global uncaught exception handler to intercept
 *     catastrophic thread deaths bypassing reactive contexts.</li>
 * <li><b>Cryptographic Provider Injection:</b> Registers the Bouncy Castle security provider.</li>
 * </ul>
 *
 * @author Thinklab Systems Engineering Team
 * @version 3.5.0
 * @since 1.0
 */
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    /**
     * Main method to launch the Micronaut runtime and enforce global JVM baseline constraints.
     *
     * @param args Command line arguments passed during startup. Must not be null.
     */
    public static void main(String[] args) {

        // 1. Enforce UTC globally to prevent time-drift bugs
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 2. SRE Forensics: Inject System Boot Context into the MDC to eliminate [NONE] tags during warmup
        try {
            MDC.put("traceId", "SYSTEM-BOOT");
            MDC.put("clientIp", InetAddress.getLocalHost().getHostAddress());
            MDC.put("userAgent", "Micronaut-Engine/Startup");
            MDC.put("ip", InetAddress.getLocalHost().getHostAddress());
            MDC.put("client", "Micronaut-Engine/Startup");
        } catch (Exception e) {
            MDC.put("clientIp", "INTERNAL-MESH");
            MDC.put("ip", "INTERNAL-MESH");
        }

        // 3. Enable automatic MDC propagation hooks & Reactor-to-MDC bridge
        Hooks.enableAutomaticContextPropagation();
        ReactorMdcBridge.register();

        // 4. Catch catastrophic thread deaths that bypass the Reactive context
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                log.error("[JVM_FATAL] - Unhandled exception in thread [{}]: {}", thread.getName(), throwable.getMessage(), throwable)
        );

        // 5. Inject Bouncy Castle to satisfy native JVM cryptographic dependencies
        Security.addProvider(new BouncyCastleProvider());

        // 6. Boot: Use the Builder for explicit control over startup arguments and container lifecycle
        try {
            Micronaut.build(args)
                    .mainClass(Application.class)
                    .start();
        } catch (Exception bootException) {
            log.error("[BOOT_FAILED] - Application container failed to initialize: {}", bootException.getMessage(), bootException);
            System.exit(1);
        }
    }
}

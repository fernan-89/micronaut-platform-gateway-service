package com.thinklab.gateway;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Binds {@code gateway.session-cookie.*} (ADR-025): the HttpOnly cookie that carries the refresh token between the browser and the
 * gateway, so the page's scripts never see it.
 *
 * <p>{@code path} is the path as the BROWSER sees it (the web app reaches the gateway under {@code /api}); a cookie is only sent back to
 * that path. {@code secure} is on by default and is switched off only for plain-http local development. {@code completeUrl} is where
 * the browser lands after a federated sign-in (the web app, which then fetches its access token), {@code loginUrl} where it lands when
 * the sign-in failed.
 */
@ConfigurationProperties("gateway.session-cookie")
public class SessionCookieProperties {

    private String name = "thinklab_rt";
    private String path = "/api/gateway/v1/session";
    private boolean secure = true;
    private String completeUrl = "/sso/complete";
    private String loginUrl = "/login";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getCompleteUrl() {
        return completeUrl;
    }

    public void setCompleteUrl(String completeUrl) {
        this.completeUrl = completeUrl;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }
}

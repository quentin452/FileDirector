package net.jan.moddirector.core.configuration.type;

import java.net.URL;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class RemoteConfig
{
    private final URL url;

    @JsonCreator
    public RemoteConfig(
        @JsonProperty(value = "url", required = true) URL url
    ) {
        this.url = url;
    }

    public URL getUrl() {
        return url;
    }
}
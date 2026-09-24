package org.extism.sdk.chicory;


import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;

import run.endive.wasm.WasmModule;

public abstract class ManifestWasm {

    public static Builder fromPath(String path) {
        var wasm = new ManifestWasmPath(path);
        return new Builder(wasm);
    }

    public static Builder fromUrl(String url) {
        var wasm = new ManifestWasmUrl(url);
        return new Builder(wasm);
    }

    public static Builder fromFilePath(Path path) {
        var wasm = new ManifestWasmFile(path);
        return new Builder(wasm);
    }

    public static Builder fromBytes(byte[] bytes) {
        var wasm = new ManifestWasmBytes(bytes);
        return new Builder(wasm);
    }

    // FlowCatalyst change (see NOTICE): an already-parsed module, so every plugin
    // instantiated from it shares ONE WasmModule — and therefore one compiled machine
    // factory keyed by that module — instead of re-parsing (and re-compiling) per instance.
    public static Builder fromModule(WasmModule module) {
        var wasm = new ManifestWasmModule(module);
        return new Builder(wasm);
    }

    public static class Builder {
        private final ManifestWasm wasm;

        public Builder(ManifestWasm wasm) {
            this.wasm = wasm;
        }

        public Builder withName(String name) {
            this.wasm.name = name;
            return this;
        }

        public ManifestWasm build() {
            return this.wasm;
        }

    }

    String name;

}

class ManifestWasmBytes extends ManifestWasm {
    final byte[] bytes;

    public ManifestWasmBytes(byte[] bytes) {
        this.bytes = bytes;
    }
}

class ManifestWasmFile extends ManifestWasm {
    final Path filePath;

    public ManifestWasmFile(Path path) {
        this.filePath = path;
    }
}

class ManifestWasmPath extends ManifestWasm {
    final String path;

    public ManifestWasmPath(String path) {
        this.path = path;
    }
}

// FlowCatalyst change (see NOTICE): see ManifestWasm#fromModule.
class ManifestWasmModule extends ManifestWasm {
    final WasmModule module;

    public ManifestWasmModule(WasmModule module) {
        this.module = module;
    }
}

class ManifestWasmUrl extends ManifestWasm {
    final String url;

    public ManifestWasmUrl(String url) {
        this.url = url;
    }

    public InputStream getUrlAsStream() {
        try {
            var url = new URL(this.url);
            return url.openStream();
        } catch (MalformedURLException e) {
            throw new ExtismException(e);
        } catch (IOException e) {
            throw new ExtismException(e);
        }

    }
}
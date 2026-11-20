package com.muninn.provider.adapter;

import java.nio.file.Path;

public interface TranscriptionAdapter {

    TranscriptResult transcribe(ProviderConnection connection, Path audio, String model);
}

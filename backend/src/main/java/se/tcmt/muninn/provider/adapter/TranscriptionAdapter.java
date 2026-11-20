package se.tcmt.muninn.provider.adapter;

import java.nio.file.Path;

public interface TranscriptionAdapter {

  /** Transcribes an audio file with the given model. */
  TranscriptResult transcribe(ProviderConnection connection, Path audio, String model);
}

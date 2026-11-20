package se.tcmt.muninn.provider.adapter;

import java.util.List;

public record TranscriptResult(String text, List<Segment> segments) {

  public record Segment(double start, double end, String text) {}
}

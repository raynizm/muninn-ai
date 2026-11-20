package se.tcmt.muninn.provider.adapter;

import java.util.List;

public interface ModelDiscoveryAdapter {

  /** Lists the models the provider offers. */
  List<DiscoveredModel> discover(ProviderConnection c);

  /** Checks that the provider is reachable and the key works. */
  ConnectionTestResult test(ProviderConnection c);
}

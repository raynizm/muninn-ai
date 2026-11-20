package se.tcmt.muninn.provider.adapter;

import java.util.List;

public interface ModelDiscoveryAdapter {

    List<DiscoveredModel> discover(ProviderConnection c);

    ConnectionTestResult test(ProviderConnection c);
}

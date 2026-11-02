package sagex.miniclient.events;

import sagex.miniclient.party.PartyClient;

public final class PartyStateChanged {
    public final PartyClient.State state;

    public PartyStateChanged(PartyClient.State state) {
        this.state = state;
    }
}

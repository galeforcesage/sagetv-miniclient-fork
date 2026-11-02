package sagex.miniclient.party;

public final class PartyProtocolException extends Exception {
    public PartyProtocolException(String message) {
        super(message);
    }

    public PartyProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

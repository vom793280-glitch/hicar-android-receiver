package local.hicarlab;

/** Coalesce disconnects from one generation and back off repeated restarts. */
final class ReconnectGate {
    private int pending;
    private boolean requested;
    private int failures;
    private long lastRequest;
    synchronized boolean request(int generation) {
        if(requested && pending==generation)return false;
        pending=generation;requested=true;
        long now=System.currentTimeMillis();
        if(lastRequest>0 && now-lastRequest<60000)failures++;
        else failures=0;
        lastRequest=now;
        return true;
    }
    /** Milliseconds before advertising again; longer delay avoids a fast re-prompt storm. */
    synchronized long delay(){
        int f=Math.min(failures,5);
        // 4s, 8s, 16s, 30s, 45s, 60s. The first retry must leave time to type a 6-digit PIN.
        return new long[]{4000,8000,16000,30000,45000,60000}[f];
    }
    synchronized void reset(){requested=false;failures=0;lastRequest=0;}
}

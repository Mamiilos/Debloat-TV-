package io.freemytv;

public final class TraktDeviceFlowTest {
    private static int assertions;

    private static void check(boolean b, String why) {
        assertions++;
        if (!b) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        check(TraktDeviceFlow.delay(400, 5, 60) == 5, "pending uses the issued interval");
        check(TraktDeviceFlow.delay(429, 5, 2) == 10, "slow down increases the interval");
        check(TraktDeviceFlow.delay(429, 5, 60) == 60, "Retry-After is respected");
        check(TraktDeviceFlow.delay(503, 5, 30) == 30, "server failure backs off");
        for (int status : new int[] {404, 409, 410, 418, 401})
            check(TraktDeviceFlow.delay(status, 5, 60) == -1, "terminal response " + status);
        check(TraktDeviceFlow.active(1, 1, 100, 101), "unexpired request is active");
        check(!TraktDeviceFlow.active(1, 1, 101, 101), "expired code stops polling");
        check(
                !TraktDeviceFlow.active(1, 2, 100, 101),
                "cancellation or replacement invalidates late results");
        System.out.println("TraktDeviceFlow: " + assertions + " assertions passed");
    }
}

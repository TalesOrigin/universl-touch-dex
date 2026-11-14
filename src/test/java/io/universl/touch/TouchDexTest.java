package io.universl.touch;

import java.util.ArrayList;
import java.util.List;

public final class TouchDexTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        List<TouchDex.Response> replies = new ArrayList<>();
        List<String> operations = new ArrayList<>();
        TouchDex bridge = new TouchDex(new TouchDex.Backend() {
            public void send(TouchDex owner, long id, String op, String text, int[] values) {
                operations.add(op);
                if (op.equals("init")) owner.onNativeResponse(id, TouchDex.OK, "ready");
                if (op.equals("input")) throw new IllegalStateException("backend failed");
            }
            public void close(TouchDex owner) { operations.add("close"); }
        });
        long init = bridge.init("{}", replies::add);
        check(replies.size() == 1 && replies.get(0).isSuccess());
        bridge.onNativeResponse(init, 0, "duplicate");
        check(replies.size() == 1);
        long touch = bridge.touch(TouchDex.DOWN, 0, 100, 200, replies::add);
        bridge.cancel(touch);
        bridge.onNativeResponse(touch, 0, "late");
        check(replies.size() == 2 && replies.get(1).status == TouchDex.CANCELLED);
        bridge.input("hello", replies::add);
        check(replies.get(2).status == TouchDex.ERROR);
        bridge.swipe(0, 0, 50, 50, 200, replies::add);
        bridge.swap(0, 1, replies::add);
        bridge.key(0, 66, 0, replies::add);
        bridge.scroll(0, 0, 0, -1, replies::add);
        bridge.release(replies::add);
        try { bridge.touch(8, 0, 0, 0, replies::add); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        bridge.close();
        bridge.close();
        check(replies.size() == 8);
        check(operations.size() == 9);
        check(replies.get(7).status == TouchDex.CLOSED);
        try { bridge.init("{}", replies::add); throw new AssertionError(); }
        catch (IllegalStateException expected) { }
        System.out.println("TouchDex tests passed");
    }
}

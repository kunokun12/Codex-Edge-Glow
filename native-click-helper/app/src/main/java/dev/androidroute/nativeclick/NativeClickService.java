package dev.androidroute.nativeclick;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class NativeClickService extends AccessibilityService {
    static final String TARGET_PACKAGE = "dev.androidroute.benchmark";
    static final String TARGET_LABEL = "Open Guide";
    private static volatile NativeClickService active;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        active = this;
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    static String clickOpenGuide() {
        NativeClickService service = active;
        if (service == null) return "REFUSED_SERVICE_NOT_ENABLED";
        return service.clickFromLiveTree();
    }

    private String clickFromLiveTree() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "REFUSED_NO_ACTIVE_WINDOW";
        try {
            if (root.getPackageName() == null ||
                !TARGET_PACKAGE.contentEquals(root.getPackageName())) {
                return "REFUSED_WRONG_PACKAGE";
            }
            int expectedWindow = root.getWindowId();
            if (!root.isVisibleToUser()) return "REFUSED_ROOT_NOT_VISIBLE";
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            List<AccessibilityNodeInfo> matches = new ArrayList<>();
            queue.add(root);
            int traversed = 0;
            while (!queue.isEmpty()) {
                if (++traversed > 512) return "REFUSED_TREE_TOO_LARGE";
                AccessibilityNodeInfo node = queue.removeFirst();
                if (node.isVisibleToUser() && node.isEnabled() && exactLabel(node)) {
                    AccessibilityNodeInfo clickable = findClickable(node, expectedWindow);
                    if (clickable != null) {
                        boolean duplicate = false;
                        for (AccessibilityNodeInfo old : matches) {
                            if (old.equals(clickable)) { duplicate = true; break; }
                        }
                        if (!duplicate) matches.add(clickable);
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
            }
            if (matches.size() != 1) {
                return matches.isEmpty() ? "REFUSED_NOT_FOUND_OR_NOT_CLICKABLE" : "REFUSED_AMBIGUOUS";
            }
            AccessibilityNodeInfo target = matches.get(0);
            if (target.getWindowId() != expectedWindow ||
                !target.refresh() || !target.isVisibleToUser() ||
                !target.isEnabled() || !supportsClick(target)) {
                return "REFUSED_STALE_OR_DISABLED";
            }
            // No coordinate calculation or gesture fallback. True means dispatched,
            // not verified navigation. A post-dispatch error is never auto-retried.
            boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return accepted ? "DISPATCH_ACCEPTED_UNVERIFIED" : "DISPATCH_REJECTED_UNCERTAIN";
        } catch (RuntimeException error) {
            return "EXECUTION_UNCERTAIN";
        }
    }

    private static AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node, int windowId) {
        AccessibilityNodeInfo current = node;
        for (int level = 0; current != null && level <= 5; level++) {
            if (current.getWindowId() != windowId || !current.isVisibleToUser() ||
                !current.isEnabled()) return null;
            if (supportsClick(current)) return current;
            current = current.getParent();
        }
        return null;
    }

    private static boolean supportsClick(AccessibilityNodeInfo node) {
        if (!node.isClickable()) return false;
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == AccessibilityNodeInfo.ACTION_CLICK) return true;
        }
        return false;
    }

    private static boolean exactLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        return (text != null && TARGET_LABEL.contentEquals(text)) ||
               (desc != null && TARGET_LABEL.contentEquals(desc));
    }
}

> **Fix status (2026-07-14):** #1–#16 and #18 fixed and the `:ads` module compiles.
> #9 fixed via a main-thread atomic `showSplashOnce()` guard + reset-on-show-failure in the
> waterfalls (`loadSplashOpenHighFloor`, `loadAdOpenSplash2id`). #17 (shared instance state reused
> across flows) is *mitigated* by that guard but still warrants a per-flow state-machine refactor
> with device testing. #19 left as-is (intentional: purchase state is global, the `Context` arg is
> unused by design).

# Code Review — `ads/src/main/java/com/ads/control`

Focused on real defects (crashes, hangs, races, resource leaks) rather than style.
Findings are grouped by severity, with `file:line` references.

Scope note: the `spinkit/` widget subtree is vendored animation code and out of scope.

---

## 🔴 Critical — crashes / hangs / logic breaks

### 1. `Admob.java:1319-1357` — interstitial flow hangs when app is backgrounded
In `showInterstitialAd`, when `currentClicked >= numShowAds` is true but the lifecycle is **not**
`RESUMED`, the code enters the outer `if`, skips the inner `RESUMED` block entirely, resets
`currentClicked = 0`, and **never calls `callback.onNextAction()`**. Any caller waiting on that
callback to advance navigation is stuck.

```java
if (currentClicked >= numShowAds && mInterstitialAd != null) {
    if (ProcessLifecycleOwner...isAtLeast(RESUMED)) { ... }  // skipped
    currentClicked = 0;                                       // <- no onNextAction() on the not-resumed path
} else if (callback != null) { callback.onNextAction(); }
```

### 2. `Admob.java:1339,1343,1348` — unchecked `Context` → `Activity` cast
`showInterstitialAd` takes a `Context` but casts to `AppCompatActivity`/`Activity` with no
`instanceof` guard. Passing an application/service context throws `ClassCastException`.

### 3. `AppPurchase.java:545-558` — NPE: null check after dereference
```java
ProductDetails productDetails = skuDetailsINAPMap.get(productId);
Log.d(TAG, "purchase: "+ productDetails.toString());   // NPE here
...
if (productDetails == null) { return "Product ID invalid"; }  // checked too late
```
`productDetails.toString()` runs before the null check — an invalid/not-yet-loaded product id crashes.

### 4. `AppOpenManager.java:1334 + 1384-1387` — NPE on `timeoutHandler`
`loadAndShowSplashAds` calls `timeoutHandler.removeCallbacks(...)` in the load callback, but
`timeoutHandler` is only instantiated inside `if (splashTimeout > 0)`. With `splashTimeout == 0`,
the field is null when the callback fires → NPE.

### 5. `AppOpenManager.java:1698-1719` — NPE on `currentActivity` in `onResume()`
`onActivityDestroyed` sets `currentActivity = null`. In `onResume` the disabled-list loop
dereferences `currentActivity.getClass()` (line 1699) and line 1711 does
`splashActivity.getName().equals(currentActivity.getClass()...)` guarded only on `splashActivity`,
not `currentActivity`. If ON_START arrives while `currentActivity` is null → NPE.

### 6. `AppPurchase.java:371` — inconsistent null guard → NPE
In `verifyPurchased`, the INAPP else-branch calls `billingListener.onInitBillingFinished(...)` with
**no** null check, while every sibling branch (357-364, 421-426, 433) guards
`billingListener != null`. When there's no listener yet, this branch NPEs.

---

## 🟠 High — wrong behavior / resource leaks

### 7. `AppOpenManager.java:499` — copy-paste bug nulls the wrong ad
In `showAdsWithLoading` (the *splash* path) `onAdDismissedFullScreenContent` sets
`appResumeAd = null` instead of `splashAd = null`. It destroys a valid resume ad; the splash ad is
cleared separately at line 521.

### 8. `AppOpenManager.java:539-546` — splash loading dialog never dismissed
The dismiss block for `dialogSplash` (`PrepareLoadingAdsDialog`) is fully commented out, so the
loading dialog is shown and never taken down → window leak / stuck overlay.

### 9. `AppOpenManager` waterfall can show two ads (matches last commit "Fix show 2 interstitial id")
The High/Medium/All callbacks each gate on `if (!isAppOpenShowed)` (e.g. 712-718, 750-752,
790-796, 888-894) but `isAppOpenShowed` is only set true later in `onAdImpression`. Two loads
completing close together both pass the check and both call `show()`. This is a structural
check-then-act race across the whole waterfall (`statusHigh/Medium/All`, `splashAdHigh/Medium/All`),
not a one-line fix.

### 10. `AppOpenManager` — `CountDownTimer timerListenInter` never reset/cancelled
Created under `if (timerListenInter == null)` (1056, 1584) but never set back to `null` or
`cancel()`ed on completion/destroy. A second splash session finds it non-null and the fallback timer
never starts; it also leaks the Activity.

### 11. `AppPurchase.java:780` — purchase state lost when no listener
`handlePurchase` sets `isPurchase = true` **inside** `if (purchaseListener != null)`. A successful
purchase with no listener attached leaves `isPurchase == false`, so ads keep showing to a paying user.

### 12. `AppPurchase.java:150-156` — billing callback can fire twice
`rdTimeout` calls `onInitBillingFinished(ERROR)` on timeout; `removeCallbacks(rdTimeout)` only runs
on the success paths inside `verifyPurchased`. If the timeout fires first and setup then completes
(or vice-versa), the listener is invoked twice with conflicting codes. `isInitBillingFinish` isn't
checked before firing.

### 13. `AppPurchase.java:646-647, 840, 857, 877, 901, 923` — unchecked list access
`getSubscriptionOfferDetails().get(size-1)` / `getPricingPhaseList().get(...)` assume non-null,
non-empty lists. A subscription product with no offer details throws NPE/`IndexOutOfBounds`.

---

## 🟡 Medium — robustness / dead code

### 14. `Admob.java:1244-1245, 1359-1360` — `dialog.dismiss()` without try/catch
Elsewhere dismiss is wrapped in try/catch (because the window may be gone); these call sites aren't,
and can throw `IllegalArgumentException`.

### 15. `AppOpenManager.java:87-89` & `Admob.java:120-121` — dead `Thread` fields
`threadHigh/threadMedium/threadAll` / `threadHighFloor/threadAll` are declared and never used.

### 16. `AppOpenManager.java:298-301` — duplicate assignment
`this.splashAd = ad;` immediately followed by `setSplashAd(ad)` (commented "Luan") sets the same
field twice.

### 17. Shared mutable state reused across overlapping flows
`splashAd`, `splashAdInter`, `statusInter*`, `currentClicked` etc. are single instance fields reused
by `loadSplashOpenAndInter`, `loadSplashInters`, `showAppOpenSplash`, `showResumeAds`… Running two ad
flows in the same session interferes (e.g. `onCheckShowAppOpenSplashWhenFail:1628` overwrites
`splashAd` with `splashAdHigh`). This is the root cause behind several of the races above.

### 18. `SharePreferenceUtils.java:36-41` — non-atomic read-modify-write
`updateCurrentTotalRevenueAd` does get→add→put; concurrent ad-revenue callbacks can lose updates.
Minor but real for revenue accounting.

### 19. `AppPurchase.java:312` — `isPurchased(Context)` ignores its argument
Returns the field regardless of context — harmless but misleading API; callers passing different
contexts get identical results.

---

## Notes on what's clean
`NavigationTracker.java` is fine (volatile + synchronized set; the double-read in `canShowAppOpen`
is a benign TOCTOU). The `spinkit/` widget subtree is vendored animation code and out of scope.

---

## Recommended fix order
The single highest-value fixes are **#1** (hang) and **#9/#17** (the double-show race — it won't be
fully solved without consolidating the shared `splashAd*`/`status*` fields into per-flow state or a
small state machine).

Start with **#1, #3, #4, #5, #6** — small, self-contained, each a crash/hang.

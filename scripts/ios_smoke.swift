import XCTest

final class WhumpoNativeSmoke: XCTestCase {
  let app = XCUIApplication(bundleIdentifier: "com.whumpo.expomock")
  func element(_ label: String) -> XCUIElement {
    let matches = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label))
    return matches.allElementsBoundByIndex.first(where: { $0.isHittable }) ?? matches.firstMatch
  }
  func tap(_ label: String) {
    let target = element(label)
    XCTAssertTrue(target.waitForExistence(timeout: 8), "Missing \(label)")
    target.tap()
  }
  func capture(_ name: String) {
    let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
    shot.name = name
    shot.lifetime = .keepAlways
    add(shot)
  }
  func testNativeDraftHistoryAndApproval() {
    continueAfterFailure = false
    // Expo BlurEffectView provides these test arguments to replace its intentionally
    // incomplete UIViewPropertyAnimator with a static native blur. No Detox server
    // or package runs. Normal delivery captures launch without these arguments.
    app.launchArguments = ["-detoxServer", "-detoxSessionId"]
    app.launch()
    XCTAssertTrue(element("Partner").waitForExistence(timeout: 30))
    tap("Partner")
    tap("New conversation")
    tap("Review my website draft")
    tap("Create draft in Partner")
    XCTAssertTrue(element("Draft · not sent").waitForExistence(timeout: 5))
    capture("L2-32-draft-bubble")
    tap("Open Partner history")
    XCTAssertTrue(element("Website launch").waitForExistence(timeout: 5))
    capture("history-before-swipe")
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.45)).press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.45)))
    XCTAssertTrue(element("New conversation").waitForExistence(timeout: 5))
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.03, dy: 0.5)).press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5)))
    XCTAssertTrue(element("Switch organization, Studio North").waitForExistence(timeout: 5))
    tap("Switch organization, Studio North")
    tap("Field & Form")
    XCTAssertTrue(element("Field notes website").waitForExistence(timeout: 5))
    tap("Switch organization, Field & Form")
    tap("Studio North")
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.55)).tap()
    tap("Today")
    tap("Reviews, 2 approvals")
    tap("Website introduction")
    tap("Approve change")
    capture("approval-confirmation-native-test")
    tap("Confirm approval")
    XCTAssertTrue(element("Approved, awaiting publish step").waitForExistence(timeout: 5))
    capture("L2-33-approved-detail")
    tap("Back")
    XCTAssertTrue(element("1 decision needs you").waitForExistence(timeout: 8))
    tap("Back")
    if !element("Reviews, 1 approval").waitForExistence(timeout: 5) { tap("Back") }
    XCTAssertTrue(element("Reviews, 1 approval").waitForExistence(timeout: 8))
    capture("L2-34-today-one-approval")
    tap("Partner")
    tap("New conversation")
    let composer = element("Ask your Partner, draft only")
    composer.tap()
    composer.typeText("Review the studio introduction")
    capture("L2-12-partner-composer")
    app.keyboards.buttons["Done"].tap()
  }
  func testNativeStackBack() {
    continueAfterFailure = false
    app.launchArguments = []
    app.launch()
    XCTAssertTrue(element("More").waitForExistence(timeout: 30))
    tap("More")
    tap("Website")
    XCTAssertTrue(element("Draft, not published").waitForExistence(timeout: 10))
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.5)).press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)))
    XCTAssertTrue(element("More").waitForExistence(timeout: 8))
    capture("native-stack-edge-back")
  }

}

import SwiftUI

#if DEBUG
if let dir = ProcessInfo.processInfo.environment["ZYXEL_RENDER_DIR"] { runRenderTest(into: dir) }
#endif
ZyxelRouterApp.main()

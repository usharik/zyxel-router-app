// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "ZyxelRouter",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "RouterKit"),
        .executableTarget(name: "ZyxelRouter", dependencies: ["RouterKit"]),
        .executableTarget(name: "routerprobe", dependencies: ["RouterKit"]),
        .executableTarget(name: "routercapture", dependencies: ["RouterKit"]),
        // XCTest недоступен без Xcode (только Command Line Tools), поэтому тесты — исполняемая цель
        // с простыми проверками: `swift run RouterKitTests` (выход ≠0 при провале).
        .executableTarget(name: "RouterKitTests", dependencies: ["RouterKit"]),
    ]
)

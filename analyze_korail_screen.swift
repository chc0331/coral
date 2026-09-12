import CoreGraphics
import Foundation
import ImageIO
import Vision

struct Bounds {
    let left: Int
    let top: Int
    let right: Int
    let bottom: Int

    var centerX: Int { (left + right) / 2 }
    var centerY: Int { (top + bottom) / 2 }

    func contains(x: CGFloat, y: CGFloat) -> Bool {
        x >= CGFloat(left) && x <= CGFloat(right) && y >= CGFloat(top) && y <= CGFloat(bottom)
    }
}

struct RecognizedText {
    let text: String
    let centerX: CGFloat
    let centerY: CGFloat
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

func log(_ message: String) {
    FileHandle.standardError.write(Data((message + "\n").utf8))
}

func parseBounds(_ value: String) -> Bounds? {
    let numbers = value.split { character in
        character == "[" || character == "]" || character == ","
    }.compactMap { Int($0) }
    guard numbers.count == 4 else { return nil }
    guard numbers[2] > numbers[0], numbers[3] > numbers[1] else { return nil }
    return Bounds(left: numbers[0], top: numbers[1], right: numbers[2], bottom: numbers[3])
}

func compact(_ value: String) -> String {
    value.replacingOccurrences(of: " ", with: "")
        .replacingOccurrences(of: "\n", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
}

func status(for seatLabel: String, in item: Bounds, observations: [RecognizedText]) -> String {
    let matchingLabels = observations.filter { observation in
        item.contains(x: observation.centerX, y: observation.centerY)
            && compact(observation.text).contains(seatLabel)
    }
    guard let labelObservation = matchingLabels.first else { return "확인불가" }

    let labelText = compact(labelObservation.text)
    if labelText.contains("매진") { return "매진" }

    let candidates = observations.filter { observation in
        let text = compact(observation.text)
        guard !text.isEmpty else { return false }
        guard item.contains(x: observation.centerX, y: observation.centerY) else { return false }
        guard observation.centerX > labelObservation.centerX + 20 else { return false }
        guard abs(observation.centerY - labelObservation.centerY) <= 90 else { return false }
        return !text.contains("일반실") && !text.contains("특실")
    }

    guard let nearest = candidates.min(by: {
        let lhsDistance = abs($0.centerY - labelObservation.centerY)
        let rhsDistance = abs($1.centerY - labelObservation.centerY)
        if lhsDistance == rhsDistance {
            return $0.centerX < $1.centerX
        }
        return lhsDistance < rhsDistance
    }) else {
        return "확인불가"
    }
    return nearest.text
}

func isAvailable(_ value: String) -> Bool {
    let normalized = compact(value)
    return !normalized.isEmpty && normalized != "확인불가" && normalized != "-" && !normalized.contains("매진")
}

let arguments = Array(CommandLine.arguments.dropFirst())
guard let imagePath = arguments.first else {
    fail("사용법: analyze_korail_screen.swift SCREEN_PATH ITEM_BOUNDS...")
}

let itemBounds = arguments.dropFirst().enumerated().compactMap { index, value -> (Int, Bounds)? in
    guard let bounds = parseBounds(value) else {
        log("리스트 아이템 \(index + 1)의 bounds를 해석하지 못했습니다: \(value)")
        return nil
    }
    return (index + 1, bounds)
}
guard !itemBounds.isEmpty else { fail("분석할 리스트 아이템이 없습니다.") }

let imageURL = URL(fileURLWithPath: imagePath)
guard let source = CGImageSourceCreateWithURL(imageURL as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fail("스크린샷을 읽지 못했습니다: \(imagePath)")
}

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["ko-KR", "en-US"]
request.usesLanguageCorrection = true

do {
    let handler = VNImageRequestHandler(cgImage: image, orientation: .up, options: [:])
    try handler.perform([request])
} catch {
    fail("OCR 요청을 실행하지 못했습니다: \(error)")
}

let imageWidth = CGFloat(image.width)
let imageHeight = CGFloat(image.height)
let observations: [RecognizedText] = (request.results ?? []).compactMap { observation in
    guard let candidate = observation.topCandidates(1).first else { return nil }
    let text = candidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { return nil }
    let box = observation.boundingBox
    return RecognizedText(
        text: text,
        centerX: box.midX * imageWidth,
        centerY: (1 - box.midY) * imageHeight
    )
}

var selection: (index: Int, bounds: Bounds, general: String, special: String)?
for (index, bounds) in itemBounds {
    let general = status(for: "일반실", in: bounds, observations: observations)
    let special = status(for: "특실", in: bounds, observations: observations)
    log("아이템 \(index): 일반실=\(general), 특실=\(special)")

    if selection == nil && (isAvailable(general) || isAvailable(special)) {
        selection = (index, bounds, general, special)
    }
}

if let selection {
    print("SELECT|\(selection.index)|\(selection.bounds.centerX)|\(selection.bounds.centerY)|\(selection.general)|\(selection.special)")
} else {
    print("NONE")
}

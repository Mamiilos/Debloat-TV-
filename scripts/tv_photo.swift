// One photo for the TV's screensaver: tv_photo <in> <out.jpg>
// Apple's ImageIO reads it with its orientation applied (JPEG, HEIC, PNG...), shrinks it to fit inside 1920x1080
// (never enlarges) and writes a JPEG with no metadata copied: no orientation tag, no GPS, no camera details.
// Compiled by scripts/tv_photos.py into out/bin (sips can't see a JPEG's orientation, so it can't do this).
import Foundation
import ImageIO

let args = CommandLine.arguments
guard args.count == 3,
      let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: args[1]) as CFURL, nil),
      let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
    FileHandle.standardError.write("can't read the image\n".data(using: .utf8)!)
    exit(2)
}
var width = props[kCGImagePropertyPixelWidth] as? Int ?? 0
var height = props[kCGImagePropertyPixelHeight] as? Int ?? 0
let orientation = props[kCGImagePropertyOrientation] as? Int ?? 1
if orientation >= 5 { swap(&width, &height) }   // 5 to 8 turn the picture a quarter: its upright size swaps
guard width > 0, height > 0 else { exit(2) }
let scale = min(1.0, 1920.0 / Double(width), 1080.0 / Double(height))
let maxSide = max(1, Int((Double(max(width, height)) * scale).rounded()))
let options: [CFString: Any] = [
    kCGImageSourceCreateThumbnailFromImageAlways: true,
    kCGImageSourceCreateThumbnailWithTransform: true,   // applies the orientation
    kCGImageSourceThumbnailMaxPixelSize: maxSide,
]
guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
      let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[2]) as CFURL, "public.jpeg" as CFString, 1, nil) else {
    FileHandle.standardError.write("can't convert the image\n".data(using: .utf8)!)
    exit(3)
}
CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.82] as CFDictionary)
exit(CGImageDestinationFinalize(dest) ? 0 : 4)

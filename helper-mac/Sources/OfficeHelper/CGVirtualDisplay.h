// Private CoreGraphics virtual display API (macOS 11+), used by DeskPad, BetterDisplay and Chromium's tests.
// Declarations follow Chromium ui/display/mac/test/virtual_display_util_mac.mm ("generated from CoreGraphics
// binaries") and github.com/go-macos/virtualdisplay. Private API: a macOS update may change it; Window mode is
// the fallback. Compiled into the helper as its Objective-C bridging header (-import-objc-header).

#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

@class CGVirtualDisplay;

@interface CGVirtualDisplayDescriptor : NSObject
@property(retain, nonatomic) dispatch_queue_t queue;
@property(retain, nonatomic) NSString *name;
@property(nonatomic) unsigned int maxPixelsHigh;
@property(nonatomic) unsigned int maxPixelsWide;
@property(nonatomic) CGSize sizeInMillimeters;
@property(nonatomic) unsigned int serialNum;
@property(nonatomic) unsigned int productID;
@property(nonatomic) unsigned int vendorID;
@property(nonatomic) CGPoint whitePoint;
@property(nonatomic) CGPoint redPrimary;
@property(nonatomic) CGPoint greenPrimary;
@property(nonatomic) CGPoint bluePrimary;
@property(copy, nonatomic, nullable) void (^terminationHandler)(id _Nullable, CGVirtualDisplay * _Nullable);
@end

@interface CGVirtualDisplayMode : NSObject
@property(readonly, nonatomic) unsigned int width;
@property(readonly, nonatomic) unsigned int height;
@property(readonly, nonatomic) double refreshRate;
- (instancetype)initWithWidth:(unsigned int)width height:(unsigned int)height refreshRate:(double)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property(retain, nonatomic) NSArray<CGVirtualDisplayMode *> *modes;
@property(nonatomic) unsigned int hiDPI;
@end

@interface CGVirtualDisplay : NSObject
@property(readonly, nonatomic) CGDirectDisplayID displayID;
@property(readonly, nonatomic) unsigned int hiDPI;
@property(readonly, nonatomic) NSArray<CGVirtualDisplayMode *> *modes;
@property(readonly, nonatomic) unsigned int serialNum;
@property(readonly, nonatomic) unsigned int productID;
@property(readonly, nonatomic) unsigned int vendorID;
@property(readonly, nonatomic) NSString *name;
- (nullable instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@end

NS_ASSUME_NONNULL_END

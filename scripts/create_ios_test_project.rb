require 'xcodeproj'
require 'fileutils'
root = File.expand_path('..', __dir__)
dir = File.join(root, '.native-build/ios-ui-check')
FileUtils.mkdir_p(dir)
project = Xcodeproj::Project.new(File.join(dir, 'WhumpoSmoke.xcodeproj'))
target = project.new_target(:ui_test_bundle, 'WhumpoNativeSmoke', :ios, '16.4')
file = project.main_group.new_file(File.join(root, 'scripts/ios_polish.swift'))
target.source_build_phase.add_file_reference(file)
target.build_configurations.each do |config|
 config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'com.whumpo.expomock.nativechecks'
 config.build_settings['GENERATE_INFOPLIST_FILE'] = 'YES'
 config.build_settings['SWIFT_VERSION'] = '5.0'
 config.build_settings['CODE_SIGNING_ALLOWED'] = 'NO'
 config.build_settings['TARGETED_DEVICE_FAMILY'] = '1'
 config.build_settings['TEST_TARGET_NAME'] = ''
end
project.save
scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(target)
scheme.add_test_target(target)
scheme.save_as(project.path, 'WhumpoNativeSmoke', true)
puts project.path

Pod::Spec.new do |spec|
    spec.name                     = 'composeApp'
    spec.version                  = '1.0'
    spec.homepage                 = 'https://multipaz.org'
    spec.source                   = { :http=> ''}
    spec.authors                  = ''
    spec.license                  = ''
    spec.summary                  = 'Compose Multiplatform Wallet App'
    spec.vendored_frameworks      = 'build/cocoapods/framework/ComposeApp.framework'
    spec.libraries                = 'c++'
    spec.ios.deployment_target    = '15.5'
    spec.dependency 'GoogleMLKit/BarcodeScanning'
    spec.dependency 'GoogleMLKit/FaceDetection'
    spec.dependency 'GoogleMLKit/Vision'
    spec.dependency 'TensorFlowLiteSwift'
                
    if !Dir.exist?('build/cocoapods/framework/ComposeApp.framework') || Dir.empty?('build/cocoapods/framework/ComposeApp.framework')
        raise "

        Kotlin framework 'ComposeApp' doesn't exist yet, so a proper Xcode project can't be generated.
        'pod install' should be executed after running ':generateDummyFramework' Gradle task:

            ./gradlew :composeApp:generateDummyFramework

        Alternatively, proper pod installation is performed during Gradle sync in the IDE (if Podfile location is set)"
    end
                
    spec.xcconfig = {
        'ENABLE_USER_SCRIPT_SANDBOXING' => 'NO',
    }
                
    spec.pod_target_xcconfig = {
        'KOTLIN_PROJECT_PATH' => ':composeApp',
        'PRODUCT_MODULE_NAME' => 'ComposeApp',
    }
                
    spec.script_phases = [
        {
            :name => 'Build composeApp',
            :execution_position => :before_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
                  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
                  exit 0
                fi
                set -ev
                REPO_ROOT="$PODS_TARGET_SRCROOT"
                "$REPO_ROOT/../gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:linkDebugFrameworkIosArm64
                "$REPO_ROOT/../gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:prepareComposeResourcesTaskForCommonMain
                mkdir -p "$REPO_ROOT/build/compose/cocoapods/compose-resources/composeResources/utopiasample.composeapp.generated.resources"
                cp -r "$REPO_ROOT/build/generated/compose/resourceGenerator/preparedResources/commonMain/composeResources/"* "$REPO_ROOT/build/compose/cocoapods/compose-resources/composeResources/utopiasample.composeapp.generated.resources/"
            SCRIPT
        },
        {
            :name => 'Copy Compose Resources',
            :execution_position => :after_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                set -ev
                REPO_ROOT="$PODS_TARGET_SRCROOT"
                APP_BUNDLE_PATH="$BUILT_PRODUCTS_DIR/$PRODUCT_NAME.app"
                if [ -d "$REPO_ROOT/build/compose/cocoapods/compose-resources" ]; then
                    echo "Copying compose resources to app bundle..."
                    cp -r "$REPO_ROOT/build/compose/cocoapods/compose-resources" "$APP_BUNDLE_PATH/"
                    echo "Compose resources copied successfully"
                else
                    echo "Warning: compose-resources directory not found at $REPO_ROOT/build/compose/cocoapods/compose-resources"
                fi
            SCRIPT
        }
    ]
    spec.resources = ['build/compose/cocoapods/compose-resources']
end
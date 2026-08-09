{
  description = "Hermetic Nix Ship Android application, tests, and release tooling";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";

    # gradle2nix only for buildGradlePackage
    # lockFile generation in nix/deps-scripts.nix
    gradle2nix.url = "github:tadfisher/gradle2nix/v2";
    # android-nixpkgs.url = "github:tadfisher/android-nixpkgs/stable";
    # android-nixpkgs.inputs.nixpkgs.follows = "nixpkgs";
    # can't get it to work with ndk, hit https://github.com/tadfisher/android-nixpkgs/issues/113
  };

  outputs =
    {
      self,
      nixpkgs,
      gradle2nix,
      ...
    }:
    let
      productConfig = builtins.fromJSON (builtins.readFile ./config/product.json);
      system = "x86_64-linux"; # TODO iter attrs over [ aarch64-darwin x86_64-darwin x86_64-linux]
      inherit (nixpkgs) lib;
      pkgs = import nixpkgs {
        inherit system;
        config.android_sdk.accept_license = true;
        config.allowUnfree = true;
      };
      buildToolsVersion = productConfig.android.buildToolsVersion;
      legacyBuildToolsVersion = productConfig.android.legacyBuildToolsVersion;
      ndkVersion = productConfig.android.ndkVersion;
      aapt2buildToolsVersion = buildToolsVersion;
      android = pkgs.androidenv.composeAndroidPackages {
        includeNDK = true;
        ndkVersions = [
          ndkVersion
        ];
        platformVersions = [
          (toString productConfig.android.compileSdk)
        ];
        buildToolsVersions = [
          legacyBuildToolsVersion
          buildToolsVersion
        ];
        includeEmulator = false;
        includeSystemImages = false;
      };
      emulatorAndroid = pkgs.androidenv.composeAndroidPackages {
        includeNDK = false;
        platformVersions = [
          (toString productConfig.android.emulatorApi)
        ];
        buildToolsVersions = [
          buildToolsVersion
        ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ productConfig.android.emulatorAbi ];
      };
      jdk = pkgs.jdk11_headless;
      maestroJdk = pkgs.jdk21_headless;
      # gradle = pkgs.gradle_7.unwrapped;
      gradle = pkgs.callPackage (pkgs.gradleGen {
        version = "7.5";
        hash = "sha256-y4fyIsVYW9RoOK1Nt4RjpcXz0zbl4rmNx8DFhlJzUcI=";
        defaultJava = jdk;
      }) { };
      gradleCli = pkgs.writeShellScriptBin "gradle" ''
        exec ${gradle}/bin/gradle \
          -Dorg.gradle.project.android.aapt2FromMavenOverride=${android.androidsdk}/libexec/android-sdk/build-tools/${aapt2buildToolsVersion}/aapt2 \
          "$@"
      '';
      maestroCli = pkgs.writeShellScriptBin "maestro" ''
        export JAVA_HOME=${maestroJdk.home}
        exec ${pkgs.maestro}/bin/maestro "$@"
      '';

      # https://github.com/Cliquets/scrcpy/blob/main/flake.nix
      extraGradleFlags = [
        "--offline"
        "--no-daemon"
        # override aapt2
        "-Dorg.gradle.project.android.aapt2FromMavenOverride=${android.androidsdk}/libexec/android-sdk/build-tools/${aapt2buildToolsVersion}/aapt2"
      ];
      overrideGradleFlags =
        drv:
        drv.overrideAttrs (prev: {
          gradleFlags = (prev.gradleFlags or [ ]) ++ extraGradleFlags;
        });
      buildGradlePackage =
        args: overrideGradleFlags (gradle2nix.builders.${system}.buildGradlePackage args);
      emulatorAbi = productConfig.android.emulatorAbi;
      armSystem = productConfig.runtime.nixSystemByAndroidAbi.${productConfig.android.abi};
      emulatorSystem = productConfig.runtime.nixSystemByAndroidAbi.${emulatorAbi};
      mkOriginalBootstrap =
        abi:
        pkgs.fetchurl {
          url =
            "${productConfig.bootstrap.baseUrl}/"
            + productConfig.bootstrap.archiveNameByAndroidAbi.${abi};
          sha256 = productConfig.bootstrap.upstreamSha256ByAndroidAbi.${abi};
        };
      prootSource = pkgs.fetchFromGitHub {
        owner = productConfig.proot.repositoryOwner;
        repo = productConfig.proot.repositoryName;
        rev = productConfig.proot.revision;
        hash = productConfig.proot.sourceHash;
      };
      tallocSource = pkgs.fetchurl {
        url = productConfig.proot.tallocUrl;
        hash = productConfig.proot.tallocHash;
      };
      mkProot =
        arch: androidTriple:
        pkgs.stdenvNoCC.mkDerivation {
          pname = "proot-termux-static-${arch}-android";
          version = "unstable-${builtins.substring 0 12 productConfig.proot.revision}";
          src = prootSource;
          nativeBuildInputs = [
            android.androidsdk
            pkgs.binutils
            pkgs.gnumake
            pkgs.pkg-config
            pkgs.python3
          ];
          dontConfigure = true;
          postPatch = ''
            cat > bionic-tls-alignment.ld <<'EOF'
            SECTIONS {
              .tdata : ALIGN(${toString productConfig.proot.tlsAlignmentBytes}) {
                *(.tdata .tdata.* .gnu.linkonce.td.*)
              }
            }
            INSERT BEFORE .preinit_array;
            EOF
          '';
          buildPhase = ''
            runHook preBuild
            mkdir talloc
            tar -xzf ${tallocSource} --strip-components=1 -C talloc
            mkdir -p fake-ashmem/linux
            cat > fake-ashmem/linux/ashmem.h <<'EOF'
            #include <linux/limits.h>
            #include <linux/ioctl.h>
            #include <string.h>
            #define __ASHMEMIOC 0x77
            #define ASHMEM_NAME_LEN 256
            #define ASHMEM_SET_NAME _IOW(__ASHMEMIOC, 1, char[ASHMEM_NAME_LEN])
            #define ASHMEM_SET_SIZE _IOW(__ASHMEMIOC, 3, size_t)
            #define ASHMEM_GET_SIZE _IO(__ASHMEMIOC, 4)
            EOF
            substituteInPlace src/arch.h \
              --replace-fail '#define HAS_LOADER_32BIT true' ""

            toolchain=${android.androidsdk}/libexec/android-sdk/ndk-bundle/toolchains/llvm/prebuilt/linux-x86_64/bin
            cc="$toolchain/${androidTriple}${toString productConfig.proot.androidApi}-clang"
            ar="$toolchain/llvm-ar"
            strip="$toolchain/llvm-strip"
            (
              cd talloc
              export CC="$cc"
              export AR="$ar"
              export PYTHONHASHSEED=1
              cat > cross-answers.txt <<'EOF'
            Checking uname sysname type: "Linux"
            Checking uname machine type: "android"
            Checking uname release type: "android"
            Checking uname version type: "android"
            Checking simple C program: OK
            building library support: OK
            Checking for large file support: OK
            Checking for -D_FILE_OFFSET_BITS=64: OK
            Checking for WORDS_BIGENDIAN: OK
            Checking for C99 vsnprintf: OK
            Checking for HAVE_SECURE_MKSTEMP: OK
            rpath library support: OK
            -Wl,--version-script support: FAIL
            Checking correct behavior of strtoll: OK
            Checking correct behavior of strptime: OK
            Checking for HAVE_IFACE_GETIFADDRS: OK
            Checking for HAVE_IFACE_IFCONF: OK
            Checking for HAVE_IFACE_IFREQ: OK
            Checking getconf LFS_CFLAGS: OK
            Checking for large file support without additional flags: OK
            Checking for working strptime: OK
            Checking for HAVE_SHARED_MMAP: OK
            Checking for HAVE_MREMAP: OK
            Checking for HAVE_INCOHERENT_MMAP: OK
            Checking getconf large file support flags work: OK
            EOF
              python ./buildtools/bin/waf configure \
                --disable-rpath \
                --disable-python \
                --cross-compile \
                --cross-answers=cross-answers.txt
              python ./buildtools/bin/waf build
            )
            "$ar" rcs libtalloc.a talloc/bin/default/talloc.c.*.o
            make -C src V=1 \
              CC="$cc" \
              LD="$cc" \
              OBJCOPY="$toolchain/llvm-objcopy" \
              OBJDUMP="$toolchain/llvm-objdump" \
              STRIP="$strip" \
              CFLAGS="-O3 -static -I../fake-ashmem -I../talloc" \
              LDFLAGS="-static -L.. -ltalloc -Wl,-z,noexecstack \
                -Wl,-T,$PWD/bionic-tls-alignment.ld \
                -Wl,-z,max-page-size=${toString productConfig.proot.elfPageAlignmentBytes} \
                -Wl,-z,common-page-size=${toString productConfig.proot.elfPageAlignmentBytes}"
            runHook postBuild
          '';
          installPhase = ''
            runHook preInstall
            tls_alignment="$(readelf -lW src/proot | awk '$1 == "TLS" { print $NF }')"
            if [[ -z "$tls_alignment" ]] \
              || (( tls_alignment < ${toString productConfig.proot.tlsAlignmentBytes} )); then
              echo "proot PT_TLS alignment is invalid: ''${tls_alignment:-missing}" >&2
              exit 1
            fi
            while read -r load_alignment; do
              if (( load_alignment < ${toString productConfig.proot.elfPageAlignmentBytes} )); then
                echo "proot PT_LOAD alignment is invalid: $load_alignment" >&2
                exit 1
              fi
            done < <(readelf -lW src/proot | awk '$1 == "LOAD" { print $NF }')
            install -D -m 0755 src/proot "$out/bin/proot-static"
            runHook postInstall
          '';
        };
      mkBootstrap =
        name: abi: arch:
        let
          original = mkOriginalBootstrap abi;
          androidTriple =
            if arch == "aarch64" then "aarch64-linux-android" else "x86_64-linux-android";
          proot = mkProot arch androidTriple;
        in
        pkgs.runCommand name
          {
            nativeBuildInputs = [
              pkgs.unzip
              pkgs.zip
            ];
          }
          ''
            mkdir extracted
            cd extracted
            unzip -q ${original}
            chmod -R u+w .
            substituteInPlace bin/login usr/lib/login-inner \
              --replace-fail \
                '/data/data/${productConfig.bootstrap.upstreamApplicationId}/' \
                '/data/data/${productConfig.android.applicationId}/'
            if grep -R -a -F \
              '/data/data/${productConfig.bootstrap.upstreamApplicationId}/' .; then
              echo "The repackaged bootstrap still contains paths for the upstream application ID." >&2
              exit 1
            fi
            install -m 0755 ${proot}/bin/proot-static bin/proot-static
            # ZIP records local mtimes and traversal order. Normalize both so the
            # checksum is identical on developer machines and fresh CI runners.
            export TZ=UTC
            find . -exec touch -h -d '1980-01-01 00:00:00 UTC' {} +
            find . -mindepth 1 -print | LC_ALL=C sort | zip -q -X -9 "$out" -@
          '';
      armBootstrap =
        mkBootstrap "nix-bootstrap-arm64.zip" productConfig.android.abi "aarch64";
      emulatorBootstrap =
        mkBootstrap "nix-bootstrap-x86-64.zip" emulatorAbi "x86_64";
      armPkgs = import nixpkgs { system = armSystem; };
      controlPlaneSource = (
        builtins.fetchTree {
          type = "git";
          url = productConfig.controlPlane.repositoryUrl;
          rev = productConfig.controlPlane.revision;
          narHash = productConfig.controlPlane.sourceHash;
        }
      ).outPath;
      mkControlPlanePackage =
        runtimeSystem: source:
        let
          controlPlaneLock = builtins.fromJSON (builtins.readFile "${source}/flake.lock");
          nixpkgsNodeName = controlPlaneLock.nodes.root.inputs.nixpkgs;
          lockedNixpkgs = controlPlaneLock.nodes.${nixpkgsNodeName}.locked;
          controlPlaneNixpkgs = builtins.fetchTree lockedNixpkgs;
          packagePkgs = import controlPlaneNixpkgs { system = runtimeSystem; };
          packageDefinition = "${source}/nixship.nix";
        in
        import packageDefinition {
          pkgs = packagePkgs;
          self = source;
          systems = [ runtimeSystem ];
        };
      controlPlanePackage = mkControlPlanePackage emulatorSystem controlPlaneSource;
      armControlPlanePackage =
        mkControlPlanePackage armSystem controlPlaneSource;
      mkRuntimeBundle =
        name: runtimeSystem: runtimePackage:
        let
          runtimeClosureInfo = pkgs.closureInfo { rootPaths = [ runtimePackage ]; };
        in
        pkgs.runCommand name
          {
            __structuredAttrs = true;
            nativeBuildInputs = [
              pkgs.gzip
              pkgs.jq
              pkgs.nix
            ];
            exportReferencesGraph.runtimeClosure = [ runtimePackage ];
          }
          ''
            set -o pipefail
            out=''${outputs[out]}
            mkdir -p "$out" "$TMPDIR/nix-state/profiles" "$TMPDIR/nix-state/db"
            export NIX_STATE_DIR="$TMPDIR/nix-state"
            nix-store --load-db < ${runtimeClosureInfo}/registration
            nix-store --export $(cat ${runtimeClosureInfo}/store-paths) |
              gzip -n -6 > "$out/runtime.nar.bundle"
            archive_sha256="$(sha256sum "$out/runtime.nar.bundle" | cut -d ' ' -f 1)"
            archive_bytes="$(stat -c %s "$out/runtime.nar.bundle")"
            jq -n \
              --arg repository '${productConfig.controlPlane.repositoryUrl}' \
              --arg branch '${productConfig.controlPlane.branch}' \
              --arg revision '${productConfig.controlPlane.revision}' \
              --arg system '${runtimeSystem}' \
              --arg storePath '${runtimePackage}' \
              --arg archiveSha256 "$archive_sha256" \
              --argjson archiveBytes "$archive_bytes" \
              '{
                schemaVersion: 1,
                repository: $repository,
                branch: $branch,
                revision: $revision,
                system: $system,
                storePath: $storePath,
                archive: "runtime.nar.bundle",
                archiveSha256: $archiveSha256,
                archiveBytes: $archiveBytes
              }' > "$out/runtime.json"
          '';
      runtimeBundle = mkRuntimeBundle "nix-runtime-arm64" armSystem armControlPlanePackage;
      emulatorRuntimeBundle =
        mkRuntimeBundle "nix-runtime-x86-64" emulatorSystem controlPlanePackage;
      mkSourceWithRuntime =
        name: bundle: bootstrap:
        pkgs.runCommand name { } ''
          cp -R ${lib.cleanSource ./.} "$out"
          chmod -R u+w "$out"
          asset_root="$out/app/src/main/assets/${productConfig.controlPlane.assetDirectory}"
          bootstrap_root="$out/app/src/main/assets/${productConfig.bootstrap.assetDirectory}"
          mkdir -p "$asset_root"
          mkdir -p "$bootstrap_root"
          cp ${bundle}/runtime.json ${bundle}/runtime.nar.bundle "$asset_root/"
          cp ${bootstrap} "$bootstrap_root/${productConfig.bootstrap.assetName}"
        '';
      sourceWithControlPlane =
        mkSourceWithRuntime "nixship-android-source" runtimeBundle armBootstrap;
      emulatorSourceWithControlPlane =
        mkSourceWithRuntime "nixship-android-emulator-source" emulatorRuntimeBundle emulatorBootstrap;
      mkAndroidPackage =
        name: abi: source: gradleTask:
        buildGradlePackage {
          pname = name;
          version = productConfig.android.versionName;
          src = source;
          lockFile = ./nix/gradle.lock;

          inherit gradle;
          buildJdk = jdk;

          ANDROID_SDK_ROOT = "${android.androidsdk}/libexec/android-sdk";
          ANDROID_NDK_ROOT = "${android.androidsdk}/ndk-bundle";
          ANDROID_ABI = abi;
          nativeBuildInputs = [ android.androidsdk ];
          gradleBuildFlags = [ gradleTask ];

          installPhase = ''
            mkdir $out
            cp -r app/build/outputs/* $out
          '';
        };

      # TODO use newScope or overlays like status-im app does
      scripts = pkgs.callPackage ./nix/deps-scripts.nix {
        inherit gradle;
        go-maven-resolver = self.packages.${system}.go-maven-resolver;
      };
      devPackages =
        androidSdk: platformTools:
        [
          maestroCli
          gradleCli
          androidSdk
          platformTools
          pkgs.actionlint
          pkgs.binutils
          pkgs.curl
          pkgs.gitMinimal
          pkgs.imagemagick
          pkgs.jq
          pkgs.nix-prefetch-git
          pkgs.openssl
          pkgs.shellcheck
          pkgs.syft
          pkgs.unzip
          pkgs.yq-go

          scripts.resolve-gradle-deps
          scripts.regen-lock
          scripts.build-apk

          scripts.url2json
          scripts.gen-deps-lock
          self.packages.${system}.go-maven-resolver
        ];
      emulatorPackages = [
        maestroCli
        emulatorAndroid.androidsdk
        emulatorAndroid.platform-tools
        pkgs.binutils
        pkgs.coreutils
        pkgs.findutils
        pkgs.gawk
        pkgs.gnugrep
        pkgs.gnused
        pkgs.jq
        pkgs.openssl
        pkgs.unzip
      ];
    in
    {
      packages.${system} = {
        default =
          mkAndroidPackage "nixship-android" productConfig.android.abi sourceWithControlPlane
            "assembleRelease";
        emulator-apk =
          mkAndroidPackage "nixship-android-emulator" emulatorAbi emulatorSourceWithControlPlane
            "assembleDebug";
        bundled-source = sourceWithControlPlane;
        emulator-source = emulatorSourceWithControlPlane;
        bootstrap = armBootstrap;
        emulator-bootstrap = emulatorBootstrap;
        proot-aarch64 = mkProot "aarch64" "aarch64-linux-android";
        proot-x86_64 = mkProot "x86_64" "x86_64-linux-android";
        control-plane = controlPlanePackage;
        control-plane-runtime = emulatorRuntimeBundle;
        go-maven-resolver = pkgs.callPackage ./nix/go-maven-resolver.nix { };
      };
      packages.${armSystem}.control-plane = armControlPlanePackage;
      devShells.${system} = {
        default = pkgs.mkShellNoCC {
          JAVA_HOME = jdk.home;
          ANDROID_SDK_ROOT = "${android.androidsdk}/libexec/android-sdk";
          ANDROID_NDK_ROOT = "${android.androidsdk}/ndk-bundle";
          packages = devPackages android.androidsdk android.platform-tools;
        };
        emulator = pkgs.mkShellNoCC {
          JAVA_HOME = maestroJdk.home;
          ANDROID_SDK_ROOT = "${emulatorAndroid.androidsdk}/libexec/android-sdk";
          packages = emulatorPackages;
        };
        runtime-import = pkgs.mkShellNoCC {
          packages = [
            pkgs.coreutils
            pkgs.gzip
            pkgs.jq
            pkgs.nix
          ];
        };
      };
      devShells.${armSystem}.runtime-export = armPkgs.mkShellNoCC {
        packages = [
          armPkgs.coreutils
          armPkgs.gzip
          armPkgs.jq
          armPkgs.nix
        ];
      };
      checks.${system}.configuration = pkgs.runCommand "nixship-android-configuration-check" {
        nativeBuildInputs = [
          pkgs.coreutils
          pkgs.jq
        ];
      } ''
        jq -e '
          .android.applicationId | test("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        ' ${./config/product.json} >/dev/null
        jq -e '
          .bootstrap.upstreamApplicationId |
          test("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        ' ${./config/product.json} >/dev/null
        grep -Fq \
          'TERMUX_PACKAGE_NAME = "${productConfig.android.applicationId}"' \
          ${./termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java}
        grep -Fq \
          '<!ENTITY TERMUX_PACKAGE_NAME "${productConfig.android.applicationId}">' \
          ${./app/src/main/res/values/strings.xml}
        grep -Fq \
          '<!ENTITY TERMUX_PACKAGE_NAME "${productConfig.android.applicationId}">' \
          ${./termux-shared/src/main/res/values/strings.xml}
        test "$(grep -Fc \
          'android:targetPackage="${productConfig.android.applicationId}"' \
          ${./app/src/main/res/xml/shortcuts.xml})" -eq 3
        grep -Fq \
          'android:name="${productConfig.android.applicationId}.app.failsafe_session"' \
          ${./app/src/main/res/xml/shortcuts.xml}
        jq -e '
          .android.launchActivity |
          test("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        ' ${./config/product.json} >/dev/null
        jq -e '.android.abi == "arm64-v8a"' ${./config/product.json} >/dev/null
        jq -e '.android.emulatorAbi == "x86_64"' ${./config/product.json} >/dev/null
        jq -e '.android.emulatorApi >= .android.minSdk' ${./config/product.json} >/dev/null
        jq -e '
          .android.versionName |
          test("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$")
        ' ${./config/product.json} >/dev/null
        jq -e '
          .bootstrap.upstreamSha256ByAndroidAbi["arm64-v8a"] | test("^[0-9a-f]{64}$")
        ' ${./config/product.json} >/dev/null
        jq -e '
          .bootstrap.upstreamSha256ByAndroidAbi.x86_64 | test("^[0-9a-f]{64}$")
        ' ${./config/product.json} >/dev/null
        jq -e '
          .bootstrap.embeddedSha256ByAndroidAbi["arm64-v8a"] | test("^[0-9a-f]{64}$")
        ' ${./config/product.json} >/dev/null
        jq -e '
          .bootstrap.embeddedSha256ByAndroidAbi.x86_64 | test("^[0-9a-f]{64}$")
        ' ${./config/product.json} >/dev/null
        verify_bootstrap() {
          label="$1"
          expected="$2"
          archive="$3"
          actual="$(sha256sum "$archive" | cut -d ' ' -f 1)"
          if [[ "$actual" != "$expected" ]]; then
            echo "$label bootstrap checksum mismatch: expected $expected, got $actual" >&2
            exit 1
          fi
        }
        verify_bootstrap \
          arm64-v8a \
          '${productConfig.bootstrap.embeddedSha256ByAndroidAbi."arm64-v8a"}' \
          ${armBootstrap}
        verify_bootstrap \
          x86_64 \
          '${productConfig.bootstrap.embeddedSha256ByAndroidAbi.x86_64}' \
          ${emulatorBootstrap}
        jq -e '
          .controlPlane.branch == "master" and
          (.runtime.nixSystemByAndroidAbi[.android.abi] == "aarch64-linux") and
          (.runtime.nixSystemByAndroidAbi[.android.emulatorAbi] == "x86_64-linux") and
          (.proot.repositoryOwner | test("^[A-Za-z0-9_.-]+$")) and
          (.proot.repositoryName | test("^[A-Za-z0-9_.-]+$")) and
          (.proot.revision | test("^[0-9a-f]{40}$")) and
          (.proot.sourceHash | startswith("sha256-")) and
          (.proot.tallocUrl | startswith("https://")) and
          (.proot.tallocHash | startswith("sha256-")) and
          (.proot.androidApi >= .android.minSdk) and
          (.proot.androidApi <= .android.targetSdk) and
          (.proot.tlsAlignmentBytes == 64) and
          (.proot.elfPageAlignmentBytes == 16384) and
          (.runtime.archiveMaxBytes >= 104857600 and .runtime.archiveMaxBytes <= 2147483648) and
          (.runtime.nixConfig == "experimental-features = nix-command flakes") and
          (.android.emulatorMemoryMb >= 2048 and .android.emulatorMemoryMb <= 8192) and
          (.android.emulatorDataPartitionGb >= 12 and .android.emulatorDataPartitionGb <= 32) and
          (.android.emulatorDisabledPackages | type == "array") and
          (.android.emulatorDisabledPackages | length >= 1) and
          (.android.emulatorDisabledPackages | length == (unique | length)) and
          (all(.android.emulatorDisabledPackages[];
            test("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))) and
          (.branding.owner | test("^[A-Za-z][A-Za-z .-]{1,99}$")) and
          (.branding.foregroundColor | test("^#[0-9A-F]{6}$")) and
          (.branding.backgroundColor | test("^#[0-9A-F]{6}$")) and
          (.branding.foregroundColor != .branding.backgroundColor) and
          (.controlPlane.repositoryUrl | startswith("https://github.com/")) and
          (.server.port >= 1024 and .server.port <= 65535) and
          (.server.allowNodeInterfaceDiscovery == false) and
          (.webView.loopbackHost == "127.0.0.1") and
          (.webView.healthPath | startswith("/")) and
          (.webView.setupClaimPath | startswith("/")) and
          (.webView.quickTunnelHostSuffix | test("^\\.[a-z0-9.-]+$")) and
          (.webView.pollIntervalMillis >= 250) and
          (.webView.startupTimeoutSeconds >= 60 and .webView.startupTimeoutSeconds <= 7200) and
          (.webView.setupTokenMinCharacters >= 8) and
          (.webView.setupTokenMinCharacters <= .webView.setupTokenMaxBytes) and
          (.webView.setupTokenMaxBytes >= 32 and .webView.setupTokenMaxBytes <= 4096) and
          (.diagnostics.refreshIntervalMillis >= 250 and .diagnostics.refreshIntervalMillis <= 5000) and
          (.diagnostics.stalledAfterSeconds >= 30) and
          (.diagnostics.eventLogMaxBytes >= 65536 and .diagnostics.eventLogMaxBytes <= 1048576) and
          (.diagnostics.displayTailBytes >= 8192) and
          (.diagnostics.displayTailBytes <= .diagnostics.eventLogMaxBytes) and
          (.diagnostics.processExitTailCharacters >= 256 and
            .diagnostics.processExitTailCharacters <= 4096) and
          (.acceptance.repositoryUrl | startswith("https://github.com/")) and
          (.acceptance.branch | length > 0) and
          (.acceptance.flakeOutput | length > 0) and
          (.acceptance.readyText | length > 0) and
          (.acceptance.deploymentTimeoutMillis >= 60000) and
          (.acceptance.quickTunnelTimeoutMillis >= 60000) and
          (.acceptance.readyTimeoutMillis >= 30000) and
          (.acceptance.statusReportIntervalSeconds >= 10) and
          (.acceptance.statusReportIntervalSeconds <= 300)
        ' ${./config/product.json} >/dev/null
        test -f ${controlPlaneSource}/flake.nix
        test -f ${controlPlaneSource}/flake.lock
        mkdir "$out"
        cp ${./config/product.json} "$out/product.json"
        jq -n \
          --arg repository '${productConfig.controlPlane.repositoryUrl}' \
          --arg branch '${productConfig.controlPlane.branch}' \
          --arg revision '${productConfig.controlPlane.revision}' \
          '{repository: $repository, branch: $branch, revision: $revision}' \
          > "$out/android-build.json"
      '';
    };
}

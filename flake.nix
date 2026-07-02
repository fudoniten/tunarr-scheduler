{
  description = ''
    Tunarr Scheduler -- Schedule Tunarr channels with LLM
      assistance.'';

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-25.11";
    utils.url = "github:numtide/flake-utils";
    nix-helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, nix-helpers, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages."${system}";
        helpers = nix-helpers.legacyPackages."${system}";
        cljLibs = { };

        # Version information (git commit + timestamp)
        versionInfo = let
          gitCommit = self.rev or self.dirtyRev or "unknown";
          gitTimestamp = if self ? lastModified then
          # Format: YYYYMMDD-HHMMSS
            let
              ts = toString self.lastModified;
              # lastModified is Unix epoch, convert to readable format
              year = builtins.substring 0 4 ts;
              month = builtins.substring 4 2 ts;
              day = builtins.substring 6 2 ts;
            in "${year}${month}${day}"
          else
            "dev";
          versionTag = "${builtins.substring 0 7 gitCommit}-${gitTimestamp}";
        in { inherit gitCommit gitTimestamp versionTag; };
      in {
        packages = rec {
          default = tunarrScheduler;

          tunarrScheduler = helpers.mkClojureBin {
            name = "org.fudo/tunarr-scheduler";
            primaryNamespace = "tunarr.scheduler.main";
            src = ./.;
          };

          migratusRunner = helpers.mkClojureBin {
            name = "org.fudo/tunarr-scheduler-migrate";
            primaryNamespace = "app.migrate";
            src = ./.;
          };

          deployContainer = let version = versionInfo;
          in helpers.deployContainers {
            name = "tunarr-scheduler";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" version.versionTag ];
            # Keep ffmpeg on PATH so the bumper generation service can resolve
            # a bare "ffmpeg" invocation (see tunarr.scheduler.bumpers).
            pathEnv = with pkgs; [ ffmpeg ];
            env = {
              GIT_COMMIT = version.gitCommit;
              GIT_TIMESTAMP = version.gitTimestamp;
              VERSION = version.versionTag;
              FFMPEG_PATH = "${pkgs.ffmpeg}/bin/ffmpeg";
            };
            entrypoint =
              let tunarrScheduler = self.packages."${system}".tunarrScheduler;
              in [ "${tunarrScheduler}/bin/tunarr-scheduler" ];
            verbose = true;
          };

          deployMigrationContainer = let version = versionInfo;
          in helpers.deployContainers {
            name = "tunarr-scheduler-migratus";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" version.versionTag ];
            env = {
              GIT_COMMIT = version.gitCommit;
              GIT_TIMESTAMP = version.gitTimestamp;
              VERSION = version.versionTag;
            };
            entrypoint =
              let migratus = self.packages."${system}".migratusRunner;
              in [ "${migratus}/bin/tunarr-scheduler-migrate" ];
            verbose = true;
          };

          # Combined deployment of both containers
          deployContainers = pkgs.writeShellScriptBin "deployContainers" ''
            set -euo pipefail
            echo "🚀 Deploying tunarr-scheduler containers"
            echo "Version: ${versionInfo.versionTag}"
            echo "Commit: ${versionInfo.gitCommit}"
            echo "Timestamp: ${versionInfo.gitTimestamp}"
            echo ""

            echo "📦 Building and pushing primary container..."
            ${self.packages."${system}".deployContainer}/bin/deployContainers

            echo ""
            echo "📦 Building and pushing migration container..."
            ${
              self.packages."${system}".deployMigrationContainer
            }/bin/deployContainers

            echo ""
            echo "✅ Both containers deployed successfully!"
            echo "  Primary: registry.kube.sea.fudo.link/tunarr-scheduler:${versionInfo.versionTag}"
            echo "  Migrate: registry.kube.sea.fudo.link/tunarr-scheduler-migratus:${versionInfo.versionTag}"
          '';
        };

        checks = {
          clojureTests = pkgs.runCommand "clojure-tests" { } ''
            mkdir -p $TMPDIR
            cd $TMPDIR
            ${pkgs.clojure}/bin/clojure -M:test
          '';
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = [ (helpers.updateClojureDeps cljLibs) ];
          };
          tunarrSchedulerServer = pkgs.mkShell {
            buildInputs = with self.packages."${system}"; [ tunarrScheduler ];
          };
        };

        apps = rec {
          default = deployContainers;

          deployContainers = {
            type = "app";
            program = "${
                self.packages."${system}".deployContainers
              }/bin/deployContainers";
          };

          deployContainer = {
            type = "app";
            program = "${
                self.packages."${system}".deployContainer
              }/bin/deployContainers";
          };

          deployMigrationContainer = {
            type = "app";
            program = "${
                self.packages."${system}".deployMigrationContainer
              }/bin/deployContainers";
          };
        };
      });
}

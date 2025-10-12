{
  description = ''
    Tunarr Scheduler -- Schedule Tunarr channels with LLM
      assistance.'';

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-25.05";
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
        helpers = nix-helpers.packages."${system}";
        cljLibs = { };
      in {
        packages = rec {
          default = tunarrScheduler;

          tunarrScheduler = helpers.mkClojureBin {
            name = "org.fudo/tunarr-scheduler";
            primaryNamespace = "tunarr-scheduler.cli";
            src = ./.;
          };

          migratusRunner = pkgs.writeShellApplication {
            name = "tunarr-scheduler-migratus";

            runtimeInputs = with pkgs; [ clojure ];

            text = ''
              set -euo pipefail

              default_config="${./resources}/migratus.edn"
              config="$MIGRATUS_CONFIG"

              if [ -z "$config" ] && [ -f "$default_config" ]; then
                config="$default_config"
              fi

              if [ -z "$config" ]; then
                echo "No Migratus config provided via MIGRATUS_CONFIG and no default found at $default_config" >&2
                exit 1
              fi

              exec ${pkgs.clojure}/bin/clojure \
                -Sdeps '{:deps {migratus/migratus {:mvn/version "1.6.3"}} :paths ["${
                  ./resources
                }"]}' \
                -M -m migratus.core migrate "$config"
            '';
          };

          deployContainer = helpers.deployContainers {
            name = "tunarr-scheduler";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            entrypoint =
              let tunarrScheduler = self.packages."${system}".tunarrScheduler;
              in [ "${tunarrScheduler}/bin/tunarr-scheduler" ];
            verbose = true;
          };

          migrationContainer = helpers.deployContainers {
            name = "tunarr-scheduler-migratus";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            entrypoint =
              let migratus = self.packages."${system}".migratusRunner;
              in [ "${migratus}/bin/tunarr-scheduler-migratus" ];
            verbose = true;
          };
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
          default = deployContainer;
          deployContainer = {
            type = "app";
            program =
              let deployContainer = self.packages."${system}".deployContainer;
              in "${deployContainer}/bin/deployContainers";
          };
          migrationContainer = {
            type = "app";
            program = let
              migrationContainer = self.packages."${system}".migrationContainer;
            in "${migrationContainer}/bin/deployContainers";
          };
        };
      });
}

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

          migratusRunner = pkgs.writeShellScriptBin "tunarr-scheduler-migratus" ''
            set -euo pipefail
            exec ${pkgs.clojure}/bin/clojure \
              -Sdeps '{:deps {migratus/migratus {:mvn/version "1.6.3"}}}' \
              -M -m migratus.core migrate
          '';

          deployContainer = helpers.deployContainers {
            name = "tunarr-scheduler";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" ];
            entrypoint =
              let tunarrScheduler = self.packages."${system}".tunarrScheduler;
              in [ "${tunarrScheduler}/bin/tunarr-scheduler" ];
            verbose = true;
          };

          migratusContainer = helpers.deployContainers {
            name = "tunarr-scheduler-migratus";
            repo = "registry.kube.sea.fudo.link";
            tags = [ "latest" "migrations" ];
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
          migratusContainer = {
            type = "app";
            program =
              let migratusContainer = self.packages."${system}".migratusContainer;
              in "${migratusContainer}/bin/deployContainers";
          };
        };
      });
}

#!/usr/bin/env bb
(ns setup-discord
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]))

(defn download-discord-deb []
  (let [url "https://discord.com/api/download?platform=linux&format=deb"
        output "discord.deb"]
    (println "Downloading Discord...")
    (shell "curl" "-L" url "-o" output)
    (println "Download complete.")))

(defn install-prerequisites []
  (println "Installing prerequisites...")
  (shell "sudo" "apt-get" "update")
  (shell "sudo" "apt-get" "install" "-y" "gdebi-core")
  (println "Prerequisites installed."))

(defn install-discord []
  (println "Installing Discord...")
  (shell "sudo" "gdebi" "-n" "discord.deb"))

(defn install-better-discord []
  (let [url "https://raw.githubusercontent.com/bb010g/betterdiscordctl/master/betterdiscordctl"]
    (println "Installing BetterDiscord...")
    (shell "curl" "-L" url "-o" "betterdiscordctl")
    (shell "chmod" "+x" "betterdiscordctl")
    (shell "sudo" "mv" "betterdiscordctl" "/usr/local/bin")
    (shell "betterdiscordctl" "install")))

(defn cleanup []
  (println "Cleaning up...")
  ;; Replaced fs/delete with delete-if-exists to prevent halting on cleanup
  (fs/delete-if-exists "discord.deb"))

(defn main []
  (install-prerequisites)
  (download-discord-deb)
  (install-discord)
  (install-better-discord)
  (cleanup))

(main)

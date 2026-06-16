const path = require("path");

module.exports = {
  apps: [
    {
      name: "bitch-tracker-bot",
      script: path.join(__dirname, "build/bitch-tracker-bot.js"),
      cwd: path.join(__dirname, ".config/plugins"),
      interpreter: "node",
      exec_mode: "fork",
      instances: 1,
      autorestart: true,
      max_restarts: 10,
      restart_delay: 3000,
      min_uptime: "10s",
      max_memory_restart: "512M",
      kill_timeout: 5000,
      listen_timeout: 10000,
      env: {
        NODE_ENV: "development",
        BITCH_TRACKER_SOCKET_PORT: "7878",
        BITCH_TRACKER_PUBLIC_BASE_URL: "https://radar-stealth.promethean.rest",
        OPENPLANNER_BASE_URL: "https://knoxx-stealth.promethean.rest",
        OPENPLANNER_API_KEY: process.env.OPENPLANNER_API_KEY
      },
      env_production: {
        NODE_ENV: "production",
        BITCH_TRACKER_SOCKET_PORT: "7878",
        BITCH_TRACKER_PUBLIC_BASE_URL: "https://radar-stealth.promethean.rest",
        OPENPLANNER_BASE_URL: "https://knoxx-stealth.promethean.rest",
        OPENPLANNER_API_KEY: process.env.OPENPLANNER_API_KEY
      },
      log_file: "./logs/bitch-tracker-bot-combined.log",
      out_file: "./logs/bitch-tracker-bot-out.log",
      error_file: "./logs/bitch-tracker-bot-error.log",
      log_date_format: "YYYY-MM-DD HH:mm:ss Z",
      merge_logs: true,
      time: true
    }
  ]
};

# frozen_string_literal: true

require_relative "flagbearer/version"
require_relative "flagbearer/log"
require_relative "flagbearer/http"
require_relative "flagbearer/evaluate"
require_relative "flagbearer/refresh"
require_relative "flagbearer/stream"
require_relative "flagbearer/exposures"
require_relative "flagbearer/client"

# The Flagbearer server SDK: download an environment's rules once, check flags locally.
module Flagbearer
  DEFAULT_URL = "https://sdk.flagbearer.dev"

  # Starts a client: fetches the rules and opens the stream in the background.
  def self.init(key, url: DEFAULT_URL, debug: false, app_version: nil)
    Client.new(key, url: url, debug: debug, app_version: app_version)
  end
end

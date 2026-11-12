# frozen_string_literal: true

require_relative "lib/flagbearer/version"

Gem::Specification.new do |spec|
  spec.name = "flagbearer"
  spec.version = Flagbearer::VERSION
  spec.summary = "Feature flags for Ruby, from Flagbearer"
  spec.description = "Check Flagbearer feature flags locally on the server: rules stream in, checks never wait on the network."
  spec.authors = ["Flagbearer"]
  spec.license = "MIT"
  spec.homepage = "https://flagbearer.dev"
  spec.metadata = {
    "documentation_uri" => "https://docs.flagbearer.dev/sdks/ruby",
    "rubygems_mfa_required" => "true"
  }
  spec.required_ruby_version = ">= 3.2"
  spec.files = Dir["lib/**/*.rb"] + ["LICENSE", "README.md"]
  spec.require_paths = ["lib"]
end

# Run a generated formula's test block against an existing native executable.
# This harness does not attest Homebrew installation; CI uses real brew install/test.
# QUILL_TEST_BINARY=/absolute/path/quill ruby scripts/quill_homebrew_formula_test.rb /path/quill.rb
require "pathname"
require "open3"
require "tmpdir"
require "fileutils"
require "minitest/autorun"
class Formula < Minitest::Test
  def self.desc(*); nil; end
  def self.homepage(*); nil; end
  def self.license(*); nil; end
  def self.url(*); nil; end
  def self.sha256(*); nil; end
  def self.skip_clean(*); nil; end
  def self.version(value); (@version = value); end
  def self.on_macos(&block); class_eval(&block); end
  def self.on_linux(*); nil; end
  def self.on_arm(&block); class_eval(&block); end
  def self.on_intel(*); nil; end
  def self.test(&block); define_method(:test_generated_formula, &block); end
  def shell_output(command)
    output, error, status = Open3.capture3(command)
    raise error unless status.success?
    output
  end
  def version; self.class.instance_variable_get(:@version); end
  def bin; Pathname.new(ENV.fetch("QUILL_TEST_BINARY")).parent; end
  def testpath; @testpath; end
  def setup; (@testpath = Pathname.new(Dir.mktmpdir("quill-formula-test-"))); end
  def teardown; FileUtils.remove_entry(@testpath); end
end
load ARGV.shift

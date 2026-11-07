# frozen_string_literal: true

require "test_helper"

# Puma, Unicorn and Passenger fork workers after boot, which leaves the child
# with none of the parent's threads.
class TestFork < Minitest::Test
  include ClientHelpers

  USER = { key: "user-1" }.freeze

  # Runs the block in a forked child and returns what it wrote to the pipe.
  def in_child
    reader, writer = IO.pipe
    pid = fork do
      reader.close
      writer.write(JSON.generate(yield))
      writer.close
      exit!(0)
    end
    writer.close
    result = reader.read
    Process.wait(pid)
    JSON.parse(result)
  end

  # Waits in the child, where the fake server's threads don't exist.
  def child_wait(timeout = 3)
    deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + timeout
    sleep 0.01 until yield || Process.clock_gettime(Process::CLOCK_MONOTONIC) > deadline
  end

  def test_a_child_restarts_streaming_and_gets_updates
    client = make_client
    client.sync
    assert @sdk.wait_for_stream
    # Changed without an announcement: only a new stream, whose first event is
    # the current version, tells a client about it.
    @sdk.synchronize do
      @sdk.flags = [rule("checkout", enabled: false)]
      @sdk.version = 2
    end

    result = in_child do
      # The parent's stream thread doesn't exist in the child.
      before = client.instance_variable_get(:@stream).thread.alive?
      enabled = client.enabled?("checkout", USER)
      child_wait { !client.enabled?("checkout", USER, default: true) }
      { "before" => before, "enabled" => enabled, "after" => client.enabled?("checkout", USER, default: true) }
    end
    assert_equal({ "before" => false, "enabled" => true, "after" => false }, result)
    assert client.enabled?("checkout", USER), "the parent's client is untouched"
  end

  def test_a_child_never_reports_the_parents_pending_checks
    client = make_client
    client.sync
    client.enabled?("checkout", { key: "parent-user" })

    in_child do
      client.enabled?("checkout", { key: "child-user" })
      client.close
      {}
    end
    child_users = @sdk.exposures.map { |upload| upload["user"]["key"] }
    assert_equal ["child-user"], child_users

    client.flush
    assert_equal %w[child-user parent-user], @sdk.exposures.map { |upload| upload["user"]["key"] }.sort
  end

  def test_postfork_restarts_before_the_first_check
    client = make_client
    client.sync
    assert @sdk.wait_for_stream

    in_child do
      client.postfork
      coordinator = client.instance_variable_get(:@coordinator)
      child_wait { coordinator.instance_variable_get(:@streaming) }
      {}
    end
    assert_equal 2, @sdk.stream_connections
  end
end

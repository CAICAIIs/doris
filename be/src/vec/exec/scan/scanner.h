// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <stdint.h>

#include <algorithm>
#include <vector>

#include "common/status.h"
#include "olap/tablet.h"
#include "runtime/exec_env.h"
#include "runtime/runtime_state.h"
#include "util/stopwatch.hpp"
#include "vec/core/block.h"

namespace doris {
class RuntimeProfile;
class TupleDescriptor;

namespace vectorized {
class VExprContext;
} // namespace vectorized

namespace pipeline {
class ScanLocalStateBase;
} // namespace pipeline
} // namespace doris

namespace doris::vectorized {

// Counter for load
struct ScannerCounter {
    ScannerCounter() : num_rows_filtered(0), num_rows_unselected(0) {}

    int64_t num_rows_filtered;   // unqualified rows (unmatched the dest schema, or no partition)
    int64_t num_rows_unselected; // rows filtered by predicates
};

class Scanner {
public:
    Scanner(RuntimeState* state, pipeline::ScanLocalStateBase* local_state, int64_t limit,
            RuntimeProfile* profile);

    //only used for FileScanner read one line.
    Scanner(RuntimeState* state, RuntimeProfile* profile)
            : _state(state), _limit(1), _profile(profile), _total_rf_num(0) {
        DorisMetrics::instance()->scanner_cnt->increment(1);
    };

    virtual ~Scanner() {
        SCOPED_SWITCH_THREAD_MEM_TRACKER_LIMITER(_state->query_mem_tracker());
        _input_block.clear();
        _conjuncts.clear();
        _projections.clear();
        _origin_block.clear();
        _common_expr_ctxs_push_down.clear();
        _stale_expr_ctxs.clear();
        DorisMetrics::instance()->scanner_cnt->increment(-1);
    }

    virtual Status init() { return Status::OK(); }
    // Not virtual, all child will call this method explictly
    virtual Status prepare(RuntimeState* state, const VExprContextSPtrs& conjuncts);
    virtual Status open(RuntimeState* state) {
        _block_avg_bytes = state->batch_size() * 8;
        return Status::OK();
    }

    Status get_block(RuntimeState* state, Block* block, bool* eos);
    Status get_block_after_projects(RuntimeState* state, vectorized::Block* block, bool* eos);

    virtual Status close(RuntimeState* state);

    // Try to stop scanner, and all running readers.
    virtual void try_stop() { _should_stop = true; };

    virtual std::string get_name() { return ""; }

    // return the readable name of current scan range.
    // eg, for file scanner, return the current file path.
    virtual std::string get_current_scan_range_name() { return "not implemented"; }

protected:
    // Subclass should implement this to return data.
    virtual Status _get_block_impl(RuntimeState* state, Block* block, bool* eof) = 0;

    // Update the counters before closing this scanner
    virtual void _collect_profile_before_close();

    // Filter the output block finally.
    Status _filter_output_block(Block* block);

    Status _do_projections(vectorized::Block* origin_block, vectorized::Block* output_block);

public:
    int64_t get_time_cost_ns() const { return _per_scanner_timer; }

    int64_t projection_time() const { return _projection_timer; }
    int64_t get_rows_read() const { return _num_rows_read; }

    bool is_init() const { return _is_init; }

    Status try_append_late_arrival_runtime_filter();

    // Call start_wait_worker_timer() when submit the scanner to the thread pool.
    // And call update_wait_worker_timer() when it is actually being executed.
    void start_wait_worker_timer() {
        _watch.reset();
        _watch.start();
    }

    void start_scan_cpu_timer() {
        _cpu_watch.reset();
        _cpu_watch.start();
    }

    void update_wait_worker_timer() { _scanner_wait_worker_timer += _watch.elapsed_time(); }

    int64_t get_scanner_wait_worker_timer() const { return _scanner_wait_worker_timer; }

    void update_scan_cpu_timer();

    // Some counters need to be updated realtime, for example, workload group policy need
    // scan bytes to cancel the query exceed limit.
    virtual void update_realtime_counters() {}

    RuntimeState* runtime_state() { return _state; }

    bool is_open() { return _is_open; }
    void set_opened() { _is_open = true; }

    virtual doris::TabletStorageType get_storage_type() {
        return doris::TabletStorageType::STORAGE_TYPE_REMOTE;
    }

    bool need_to_close() const { return _need_to_close; }

    void mark_to_need_to_close() {
        // If the scanner is failed during init or open, then not need update counters
        // because the query is fail and the counter is useless. And it may core during
        // update counters. For example, update counters depend on scanner's tablet, but
        // the tablet == null when init failed.
        if (_is_open) {
            _collect_profile_before_close();
        }
        _need_to_close = true;
    }

    void set_status_on_failure(const Status& st) { _status = st; }

    int64_t limit() const { return _limit; }

    auto get_block_avg_bytes() const { return _block_avg_bytes; }

    void update_block_avg_bytes(size_t block_avg_bytes) { _block_avg_bytes = block_avg_bytes; }

protected:
    void _discard_conjuncts() {
        for (auto& conjunct : _conjuncts) {
            _stale_expr_ctxs.emplace_back(conjunct);
        }
        _conjuncts.clear();
    }

    RuntimeState* _state = nullptr;
    pipeline::ScanLocalStateBase* _local_state = nullptr;

    // Set if scan node has sort limit info
    int64_t _limit = -1;

    RuntimeProfile* _profile = nullptr;

    const TupleDescriptor* _output_tuple_desc = nullptr;
    const RowDescriptor* _output_row_descriptor = nullptr;

    // If _input_tuple_desc is set, the scanner will read data into
    // this _input_block first, then convert to the output block.
    Block _input_block;

    bool _is_open = false;
    bool _is_closed = false;
    bool _need_to_close = false;
    Status _status;

    // If _applied_rf_num == _total_rf_num
    // means all runtime filters are arrived and applied.
    int _applied_rf_num = 0;
    int _total_rf_num = 0;
    // Cloned from _conjuncts of scan node.
    // It includes predicate in SQL and runtime filters.
    VExprContextSPtrs _conjuncts;
    VExprContextSPtrs _projections;
    // Used in common subexpression elimination to compute intermediate results.
    std::vector<vectorized::VExprContextSPtrs> _intermediate_projections;
    vectorized::Block _origin_block;

    VExprContextSPtrs _common_expr_ctxs_push_down;
    // Late arriving runtime filters will update _conjuncts.
    // The old _conjuncts will be temporarily placed in _stale_expr_ctxs
    // and will be destroyed at the end.
    VExprContextSPtrs _stale_expr_ctxs;

    // num of rows read from scanner
    int64_t _num_rows_read = 0;

    int64_t _num_byte_read = 0;

    // num of rows return from scanner, after filter block
    int64_t _num_rows_return = 0;

    size_t _block_avg_bytes = 0;

    // Set true after counter is updated finally
    bool _has_updated_counter = false;

    // watch to count the time wait for scanner thread
    MonotonicStopWatch _watch;
    // Do not use ScopedTimer. There is no guarantee that, the counter
    ThreadCpuStopWatch _cpu_watch;
    int64_t _scanner_wait_worker_timer = 0;
    int64_t _scan_cpu_timer = 0;

    bool _is_load = false;

    bool _is_init = true;

    ScannerCounter _counter;
    int64_t _per_scanner_timer = 0;
    int64_t _projection_timer = 0;

    bool _should_stop = false;
};

using ScannerSPtr = std::shared_ptr<Scanner>;

} // namespace doris::vectorized

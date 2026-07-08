"use strict";

function createRecognitionFilter(options) {
  options = options || {};
  var minConfidence = options.minConfidence || 0.8;
  var maxFailedReads = options.maxFailedReads || 8;
  var lastAccepted = null;
  var failedReads = 0;
  var pendingLargeDrop = null;

  function reject(reason) {
    failedReads += 1;
    return { accepted: false, reason: reason, shouldPause: failedReads >= maxFailedReads };
  }

  function update(result) {
    if (!result || !result.ok) {
      return reject(result && result.reason ? result.reason : "failed");
    }
    if (result.confidence < minConfidence) {
      return reject("low-confidence");
    }
    var value = result.timeSeconds;
    if (value < 0 || value > 90) {
      return reject("out-of-range");
    }
    if (lastAccepted === null) {
      lastAccepted = value;
      failedReads = 0;
      return { accepted: true, timeSeconds: value };
    }
    if (value === lastAccepted) {
      failedReads = 0;
      return { accepted: false, reason: "same-time" };
    }
    if (value > lastAccepted) {
      return reject("time-increased");
    }
    var drop = lastAccepted - value;
    if (drop >= 1 && drop <= 3) {
      lastAccepted = value;
      pendingLargeDrop = null;
      failedReads = 0;
      return { accepted: true, timeSeconds: value };
    }
    if (pendingLargeDrop === value) {
      lastAccepted = value;
      pendingLargeDrop = null;
      failedReads = 0;
      return { accepted: true, timeSeconds: value };
    }
    pendingLargeDrop = value;
    return { accepted: false, reason: "large-drop-needs-confirmation" };
  }

  return {
    update: update
  };
}

module.exports = {
  createRecognitionFilter: createRecognitionFilter
};

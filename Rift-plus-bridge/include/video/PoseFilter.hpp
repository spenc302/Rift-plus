#pragma once
#include <cmath>
#include <openvr_driver.h>

// RS+ Madgwick AHRS filter
// Fuses gyro + accel from the CV1 IMU into a stable quaternion for SteamVR.
//
// CV1 coordinate swizzle must be applied BEFORE calling Update():
//   engineX =  rawX
//   engineY =  rawZ   (Z becomes Y/up)
//   engineZ = -rawY   (Y negated becomes Z/forward)
//
// Tuning beta:
//   0.033  — near-stationary desktop use (very smooth, slow correction)
//   0.10   — sim racing (good balance, default)
//   0.20+  — high-motion / accel-heavy environments (faster but noisier)

class PoseFilter {
private:
    float beta = 0.1f;
    float q0 = 1.0f, q1 = 0.0f, q2 = 0.0f, q3 = 0.0f;

public:
    void SetBeta(float b) { beta = b; }

    // gx/gy/gz — rad/s (already swizzled to engine frame)
    // ax/ay/az — m/s²  (already swizzled to engine frame)
    // dt       — seconds since last call
    void Update(float gx, float gy, float gz,
                float ax, float ay, float az,
                float dt)
    {
        float recipNorm;
        float s0, s1, s2, s3;
        float qDot1, qDot2, qDot3, qDot4;
        float _2q0, _2q1, _2q2, _2q3, _4q0, _4q1, _4q2, _8q1, _8q2;
        float q0q0, q1q1, q2q2, q3q3;

        // Rate of change of quaternion from gyroscope
        qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
        qDot2 = 0.5f * ( q0 * gx + q2 * gz - q3 * gy);
        qDot3 = 0.5f * ( q0 * gy - q1 * gz + q3 * gx);
        qDot4 = 0.5f * ( q0 * gz + q1 * gy - q2 * gx);

        // Only apply gradient correction if accelerometer data is valid
        if (!((ax == 0.0f) && (ay == 0.0f) && (az == 0.0f))) {
            // Normalise accelerometer
            recipNorm = 1.0f / sqrtf(ax * ax + ay * ay + az * az);
            ax *= recipNorm;
            ay *= recipNorm;
            az *= recipNorm;

            _2q0 = 2.0f * q0; _2q1 = 2.0f * q1;
            _2q2 = 2.0f * q2; _2q3 = 2.0f * q3;
            _4q0 = 4.0f * q0; _4q1 = 4.0f * q1; _4q2 = 4.0f * q2;
            _8q1 = 8.0f * q1; _8q2 = 8.0f * q2;
            q0q0 = q0 * q0; q1q1 = q1 * q1;
            q2q2 = q2 * q2; q3q3 = q3 * q3;

            // Gradient descent corrective step
            s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay;
            s1 = _4q1 * q3q3 - _2q3 * ax + 4.0f * q0q0 * q1
               - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az;
            s2 = 4.0f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3
               - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az;
            s3 = 4.0f * q1q1 * q3 - _2q1 * ax + 4.0f * q2q2 * q3 - _2q2 * ay;

            // FIX: was `s3 *= nullptr` — must normalise all four components
            recipNorm = 1.0f / sqrtf(s0*s0 + s1*s1 + s2*s2 + s3*s3);
            s0 *= recipNorm;
            s1 *= recipNorm;
            s2 *= recipNorm;
            s3 *= recipNorm;  // ← THE FIX (was `nullptr`)

            // Apply feedback
            qDot1 -= beta * s0;
            qDot2 -= beta * s1;
            qDot3 -= beta * s2;
            qDot4 -= beta * s3;
        }

        // Integrate
        q0 += qDot1 * dt;
        q1 += qDot2 * dt;
        q2 += qDot3 * dt;
        q3 += qDot4 * dt;

        // Normalise quaternion
        recipNorm = 1.0f / sqrtf(q0*q0 + q1*q1 + q2*q2 + q3*q3);
        q0 *= recipNorm;
        q1 *= recipNorm;
        q2 *= recipNorm;
        q3 *= recipNorm;
    }

    void Reset() { q0 = 1.0f; q1 = q2 = q3 = 0.0f; }

    vr::HmdQuaternion_t GetQuaternion() const {
        return { (double)q0, (double)q1, (double)q2, (double)q3 };
    }
};

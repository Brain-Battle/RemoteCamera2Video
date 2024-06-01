/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.video.fragments

import android.annotation.SuppressLint
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.android.camera2.video.R

/**
 * In this [Fragment] we let users pick whether or not the portrait filter is on.
 */
class FilterFragment : Fragment() {

    private val args: FilterFragmentArgs by navArgs()

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View = RecyclerView(requireContext())

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view as RecyclerView
        view.apply {
            layoutManager = LinearLayoutManager(requireContext())
            val navController =
                    Navigation.findNavController(requireActivity(), R.id.fragment_container)
            if (args.dynamicRange == DynamicRangeProfiles.STANDARD) {
                navController.navigate(
                    FilterFragmentDirections.actionFilterToPreview(
                    args.cameraId, args.width, args.height, args.fps,
                    args.dynamicRange, args.colorSpace, args.previewStabilization,
                    args.useMediaRecorder, args.videoCodec, false, true, 0))
            } else {
                navController.navigate(
                    FilterFragmentDirections.actionFilterToTransfer(
                    args.cameraId, args.width, args.height, args.fps,
                    args.dynamicRange, args.colorSpace, args.previewStabilization,
                    args.useMediaRecorder, args.videoCodec, false))
            }
        }
    }
}
